package com.hammam.attendai.ai

import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.AppSettingEntity
import com.hammam.attendai.security.KeystoreCipher
import com.hammam.attendai.security.SecureSecretStore
import com.hammam.attendai.sync.BackendClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

object AiProviderNames {
    const val OPENAI="OPENAI"
    const val GEMINI="GEMINI"
    const val CLAUDE="CLAUDE"
    val all=listOf(OPENAI,GEMINI,CLAUDE)
}

enum class AiKeyMode{LOCAL_BYOK,BACKEND_MANAGED}

data class ProviderHealth(
    val provider:String,
    val configured:Boolean,
    val mode:AiKeyMode,
    val selectedModel:String?,
    val connectionStatus:String,
    val lastTest:Long?,
    val lastModelRefresh:Long?,
    val error:String?,
    val maskedKey:String?,
    val cachedModels:List<String>,
    val endpoint:String?,
)

data class HttpReply(val code:Int,val body:String){val ok get()=code in 200..299}

data class ProviderTextResult(val text:String?=null,val errorCode:String?=null){val ok:Boolean get()=!text.isNullOrBlank()&&errorCode==null}

class ProviderHttpClient{
    suspend fun request(method:String,url:String,headers:Map<String,String>,body:String?=null):HttpReply=withContext(Dispatchers.IO){
        try{
            val c=(URL(url).openConnection() as HttpURLConnection).apply{
                requestMethod=method;connectTimeout=10_000;readTimeout=30_000
                headers.forEach{(k,v)->setRequestProperty(k,v)}
                if(body!=null){
                    doOutput=true;setRequestProperty("Content-Type","application/json; charset=utf-8")
                    outputStream.use{it.write(body.toByteArray(Charsets.UTF_8))}
                }
            }
            val code=c.responseCode
            val text=runCatching{(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()}.getOrDefault("")
            HttpReply(code,text)
        }catch(e:CancellationException){
            throw e
        }catch(e:Exception){
            HttpReply(599,e.javaClass.simpleName)
        }
    }
}

class AiProviderManager(
    private val db:HammamDatabase,
    private val cipher:KeystoreCipher,
    private val secrets:SecureSecretStore,
    private val backend:BackendClient,
    private val backendConfigured:suspend ()->Boolean={true},
    private val http:ProviderHttpClient=ProviderHttpClient(),
):AiProvider{
    private val dao=db.coreDao()
    private val _revision=MutableStateFlow(0L)
    val revision=_revision.asStateFlow()

    private fun requireProvider(provider:String){require(provider in AiProviderNames.all){"UNSUPPORTED_AI_PROVIDER"}}
    private fun secretKey(provider:String)="ai.key.${provider.uppercase()}"
    private fun settingKey(provider:String,suffix:String)="ai.${provider.lowercase()}.$suffix"
    private fun touch(){_revision.value=_revision.value+1}

    suspend fun setMode(provider:String,mode:AiKeyMode){
        requireProvider(provider);put(provider,"mode",mode.name);invalidateVerification(provider);touch()
    }
    suspend fun setKey(provider:String,key:String?){
        requireProvider(provider);secrets.put(secretKey(provider),key);invalidateVerification(provider);touch()
    }
    suspend fun setSelectedModel(provider:String,model:String?){
        requireProvider(provider);put(provider,"model",model?.trim().orEmpty());invalidateVerification(provider);touch()
    }
    suspend fun setDefaultProvider(provider:String){
        requireProvider(provider);putGlobal("default_provider",provider);touch()
    }
    suspend fun setAssignment(kind:String,value:String){putGlobal("assignment.${kind.lowercase()}",value);touch()}
    suspend fun assignment(kind:String)=getGlobal("assignment.${kind.lowercase()}")?.ifBlank{null}?:"USE_DEFAULT"
    suspend fun defaultProvider()=getGlobal("default_provider")?.takeIf{it in AiProviderNames.all}?:AiProviderNames.OPENAI

    suspend fun health(provider:String):ProviderHealth{
        requireProvider(provider)
        val mode=runCatching{AiKeyMode.valueOf(get(provider,"mode")?:AiKeyMode.BACKEND_MANAGED.name)}.getOrDefault(AiKeyMode.BACKEND_MANAGED)
        val models=parseArray(get(provider,"models").orEmpty())
        val configured=if(mode==AiKeyMode.LOCAL_BYOK)secrets.configured(secretKey(provider)) else backendConfigured()
        val selectedModel=get(provider,"model")?.ifBlank{null}
        val storedStatus=get(provider,"status")
        val status=AiProviderStateRules.configurationStatus(configured,selectedModel,storedStatus)
        val storedError=get(provider,"last_error")?.ifBlank{null}
        return ProviderHealth(
            provider=provider,
            configured=configured,
            mode=mode,
            selectedModel=selectedModel,
            connectionStatus=status,
            lastTest=get(provider,"last_test")?.toLongOrNull(),
            lastModelRefresh=get(provider,"last_refresh")?.toLongOrNull(),
            error=storedError,
            maskedKey=if(mode==AiKeyMode.LOCAL_BYOK)secrets.maskedSuffix(secretKey(provider)) else null,
            cachedModels=models,
            endpoint=if(mode==AiKeyMode.LOCAL_BYOK)directEndpoint(provider) else null,
        )
    }

    suspend fun refreshModels(provider:String):List<String>{
        requireProvider(provider)
        val h=health(provider)
        if(!h.configured)error("AI_PROVIDER_NOT_CONFIGURED")
        val result=if(h.mode==AiKeyMode.LOCAL_BYOK){
            listDirect(provider,secrets.get(secretKey(provider))?:error("API_KEY_NOT_CONFIGURED"))
        }else listViaBackend(provider)
        val models=result.distinct().sorted()
        val now=System.currentTimeMillis()
        put(provider,"models",jsonArray(models));put(provider,"last_refresh",now.toString());put(provider,"last_error","")
        if(h.connectionStatus!=AiProviderStatus.CONNECTED)put(provider,"status",AiProviderStateRules.afterConfigurationMutation(true,h.selectedModel))
        touch()
        return models
    }

    /** Real connection test: the currently selected model must successfully complete a minimal request. */
    suspend fun test(provider:String):Boolean{
        requireProvider(provider)
        val now=System.currentTimeMillis()
        return try{
            val h=health(provider)
            if(!h.configured)error("AI_PROVIDER_NOT_CONFIGURED")
            val model=h.selectedModel?.takeIf{it.isNotBlank()}?:error("AI_MODEL_NOT_SELECTED")
            val facts=mapOf("healthCheck" to true)
            val text=if(h.mode==AiKeyMode.BACKEND_MANAGED){
                summarizeViaBackendChecked(provider,model,"Return a brief acknowledgement for this connectivity check.",facts)
            }else{
                val key=secrets.get(secretKey(provider))?:error("API_KEY_NOT_CONFIGURED")
                summarizeDirectChecked(provider,model,key,"Connectivity check",facts)
            }
            if(text.isBlank())error("EMPTY_PROVIDER_RESPONSE")
            put(provider,"status",AiProviderStatus.CONNECTED);put(provider,"last_error","");put(provider,"last_test",now.toString());touch();true
        }catch(e:CancellationException){throw e}
        catch(e:Exception){
            val status=AiProviderStateRules.failureStatus(sanitize(e.message))
            put(provider,"status",status);put(provider,"last_error",status);put(provider,"last_test",now.toString());touch();false
        }
    }

    suspend fun summarizeGroundedResult(prompt:String,facts:Map<String,Any?>):ProviderTextResult{
        val provider=defaultProvider();val h=health(provider)
        if(!h.configured)return ProviderTextResult(errorCode="AI_PROVIDER_NOT_CONFIGURED")
        val model=h.selectedModel?.takeIf{it.isNotBlank()}?:return ProviderTextResult(errorCode="AI_MODEL_NOT_SELECTED")
        return try{
            val text=if(h.mode==AiKeyMode.BACKEND_MANAGED) summarizeViaBackendChecked(provider,model,prompt,facts)
            else summarizeDirectChecked(provider,model,secrets.get(secretKey(provider))?:return ProviderTextResult(errorCode="AI_PROVIDER_NOT_CONFIGURED"),prompt,facts)
            ProviderTextResult(text=text)
        }catch(e:CancellationException){throw e}catch(e:Exception){
            val code=AiProviderStateRules.failureStatus(sanitize(e.message))
            put(provider,"status",code);put(provider,"last_error",code);touch()
            ProviderTextResult(errorCode=code)
        }
    }

    override suspend fun summarizeGrounded(prompt:String,facts:Map<String,Any?>):String?=summarizeGroundedResult(prompt,facts).text

    /** Optional vision extraction used by timetable import. Returns provider text only; approval remains local/human. */
    suspend fun extractTimetableVision(provider:String,mime:String,base64:String,prompt:String):String?{
        requireProvider(provider)
        val h=health(provider);val model=h.selectedModel?:return null
        if(!h.configured)return null
        return try{
            if(h.mode==AiKeyMode.BACKEND_MANAGED){
                val payload="{\"provider\":\"$provider\",\"model\":\"${esc(model)}\",\"mime\":\"${esc(mime)}\",\"image_base64\":\"$base64\",\"prompt\":\"${esc(prompt)}\"}"
                val r=backend.post("/api/v1/ai/providers/vision",payload,"vision:${payload.hashCode()}")
                if(!r.ok)error(r.error?:"PROVIDER_REQUEST_FAILED")
                extractJsonString(r.responseBody.orEmpty(),"provider_response")?:error("EMPTY_PROVIDER_RESPONSE")
            }else{
                val key=secrets.get(secretKey(provider))?:return null
                visionDirectChecked(provider,model,key,mime,base64,prompt)
            }
        }catch(e:CancellationException){throw e}catch(_:Exception){null}
    }

    private suspend fun listViaBackend(provider:String):List<String>{
        val payload="{\"provider\":\"$provider\"}"
        val r=backend.post("/api/v1/ai/providers/models",payload,"models:$provider:${System.currentTimeMillis()/300000}")
        if(!r.ok)error(r.error?:"MODEL_REFRESH_FAILED")
        return parseArrayField(r.responseBody.orEmpty(),"models")
    }

    private suspend fun listDirect(provider:String,key:String):List<String> = when(provider){
        AiProviderNames.OPENAI->{val r=http.request("GET","https://api.openai.com/v1/models",mapOf("Authorization" to "Bearer $key"));if(!r.ok)error(httpFailure(r));parseObjectIds(r.body,"id").filter(::textModelCandidate)}
        AiProviderNames.GEMINI->{val r=http.request("GET","https://generativelanguage.googleapis.com/v1beta/models",mapOf("x-goog-api-key" to key));if(!r.ok)error(httpFailure(r));parseGeminiModels(r.body)}
        AiProviderNames.CLAUDE->{val r=http.request("GET","https://api.anthropic.com/v1/models",mapOf("x-api-key" to key,"anthropic-version" to "2023-06-01"));if(!r.ok)error(httpFailure(r));parseObjectIds(r.body,"id")}
        else->emptyList()
    }

    private suspend fun summarizeViaBackendChecked(provider:String,model:String,prompt:String,facts:Map<String,Any?>):String{
        val payload="{\"provider\":\"$provider\",\"model\":\"${esc(model)}\",\"prompt\":\"${esc(prompt)}\",\"facts\":${jsonValue(facts)}}"
        val r=backend.post("/api/v1/ai/providers/summarize",payload,"ai:${payload.hashCode()}")
        if(!r.ok)error(r.error?:"PROVIDER_REQUEST_FAILED")
        return extractJsonString(r.responseBody.orEmpty(),"provider_response")?.takeIf{it.isNotBlank()}?:error("EMPTY_PROVIDER_RESPONSE")
    }

    private suspend fun summarizeDirectChecked(provider:String,model:String,key:String,prompt:String,facts:Map<String,Any?>):String{
        val grounded="Facts (authoritative): ${jsonValue(facts)}\nTask: $prompt\nNever invent numbers. If facts are insufficient, say so."
        val r=when(provider){
            AiProviderNames.OPENAI->{val body="{\"model\":\"${esc(model)}\",\"input\":\"${esc(grounded)}\"}";http.request("POST","https://api.openai.com/v1/responses",mapOf("Authorization" to "Bearer $key"),body)}
            AiProviderNames.GEMINI->{val body="{\"contents\":[{\"parts\":[{\"text\":\"${esc(grounded)}\"}]}]}";http.request("POST","https://generativelanguage.googleapis.com/v1beta/models/${encPath(model.removePrefix("models/"))}:generateContent",mapOf("x-goog-api-key" to key),body)}
            AiProviderNames.CLAUDE->{val body="{\"model\":\"${esc(model)}\",\"max_tokens\":1024,\"messages\":[{\"role\":\"user\",\"content\":\"${esc(grounded)}\"}]}";http.request("POST","https://api.anthropic.com/v1/messages",mapOf("x-api-key" to key,"anthropic-version" to "2023-06-01"),body)}
            else->error("UNSUPPORTED_AI_PROVIDER")
        }
        if(!r.ok)error(httpFailure(r))
        return (extractJsonString(r.body,"output_text")?:extractNestedText(r.body))?.takeIf{it.isNotBlank()}?:error("EMPTY_PROVIDER_RESPONSE")
    }

    private suspend fun visionDirectChecked(provider:String,model:String,key:String,mime:String,b64:String,prompt:String):String{
        val r=when(provider){
            AiProviderNames.OPENAI->{val media=if(mime.equals("application/pdf",true))"{\"type\":\"input_file\",\"filename\":\"timetable.pdf\",\"file_data\":\"data:application/pdf;base64,$b64\"}" else "{\"type\":\"input_image\",\"image_url\":\"data:${esc(mime)};base64,$b64\"}";val body="{\"model\":\"${esc(model)}\",\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"${esc(prompt)}\"},$media]}]}";http.request("POST","https://api.openai.com/v1/responses",mapOf("Authorization" to "Bearer $key"),body)}
            AiProviderNames.GEMINI->{val body="{\"contents\":[{\"parts\":[{\"text\":\"${esc(prompt)}\"},{\"inline_data\":{\"mime_type\":\"${esc(mime)}\",\"data\":\"$b64\"}}]}]}";http.request("POST","https://generativelanguage.googleapis.com/v1beta/models/${encPath(model.removePrefix("models/"))}:generateContent",mapOf("x-goog-api-key" to key),body)}
            AiProviderNames.CLAUDE->{val mediaType=if(mime.equals("application/pdf",true))"document" else "image";val body="{\"model\":\"${esc(model)}\",\"max_tokens\":2048,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"$mediaType\",\"source\":{\"type\":\"base64\",\"media_type\":\"${esc(mime)}\",\"data\":\"$b64\"}},{\"type\":\"text\",\"text\":\"${esc(prompt)}\"}]}]}";http.request("POST","https://api.anthropic.com/v1/messages",mapOf("x-api-key" to key,"anthropic-version" to "2023-06-01"),body)}
            else->error("UNSUPPORTED_AI_PROVIDER")
        }
        if(!r.ok)error(httpFailure(r))
        return (extractJsonString(r.body,"output_text")?:extractNestedText(r.body))?.takeIf{it.isNotBlank()}?:error("EMPTY_PROVIDER_RESPONSE")
    }

    private suspend fun invalidateVerification(provider:String){
        val mode=runCatching{AiKeyMode.valueOf(get(provider,"mode")?:AiKeyMode.BACKEND_MANAGED.name)}.getOrDefault(AiKeyMode.BACKEND_MANAGED)
        val configured=if(mode==AiKeyMode.LOCAL_BYOK)secrets.configured(secretKey(provider)) else backendConfigured()
        val model=get(provider,"model")?.ifBlank{null}
        put(provider,"status",AiProviderStateRules.afterConfigurationMutation(configured,model));put(provider,"last_error","");put(provider,"last_test","")
    }

    private suspend fun put(provider:String,suffix:String,value:String){dao.upsertSetting(AppSettingEntity(settingKey(provider,suffix),cipher.encrypt(value),System.currentTimeMillis()))}
    private suspend fun get(provider:String,suffix:String):String?=dao.getSetting(settingKey(provider,suffix))?.valueCiphertext?.let{runCatching{cipher.decrypt(it)}.getOrNull()}
    private suspend fun putGlobal(suffix:String,value:String){dao.upsertSetting(AppSettingEntity("ai.global.$suffix",cipher.encrypt(value),System.currentTimeMillis()))}
    private suspend fun getGlobal(suffix:String):String?=dao.getSetting("ai.global.$suffix")?.valueCiphertext?.let{runCatching{cipher.decrypt(it)}.getOrNull()}
    private fun directEndpoint(provider:String)=when(provider){AiProviderNames.OPENAI->"https://api.openai.com";AiProviderNames.GEMINI->"https://generativelanguage.googleapis.com";AiProviderNames.CLAUDE->"https://api.anthropic.com";else->null}
    private fun httpFailure(r:HttpReply):String{
        if(r.code==599)return r.body.ifBlank{"NETWORK_UNAVAILABLE"}
        val lower=r.body.lowercase()
        return when{
            r.code==401||r.code==403->AiProviderStatus.AUTH_FAILED
            r.code==429->AiProviderStatus.RATE_LIMITED
            r.code==408->AiProviderStatus.TIMEOUT
            r.code in setOf(400,404) && ("model" in lower||"not found" in lower||"unsupported" in lower)->AiProviderStatus.UNSUPPORTED_MODEL
            r.code>=500->AiProviderStatus.PROVIDER_SERVER_FAILURE
            else->"HTTP_${r.code}"
        }
    }
    private fun textModelCandidate(id:String):Boolean{val x=id.lowercase();return listOf("embedding","moderation","image","dall-e","tts","transcribe","whisper","realtime","audio","sora").none{x.contains(it)}}
    private fun parseObjectIds(json:String,key:String)=Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").findAll(json).map{it.groupValues[1]}.toList()
    private fun parseGeminiModels(json:String):List<String>{val blocks=Regex("\\{[^{}]*\\\"name\\\"\\s*:\\s*\\\"(models/[^\\\"]+)\\\"[^{}]*}").findAll(json);val all=blocks.filter{it.value.contains("generateContent")}.map{it.groupValues[1]}.toList();return if(all.isNotEmpty())all else parseObjectIds(json,"name").filter{it.startsWith("models/")}}
    private fun parseArrayField(json:String,key:String):List<String>{val m=Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\[([^]]*)]",RegexOption.DOT_MATCHES_ALL).find(json)?:return emptyList();return Regex("\\\"([^\\\"]+)\\\"").findAll(m.groupValues[1]).map{it.groupValues[1]}.toList()}
    private fun parseArray(json:String)=Regex("\\\"([^\\\"]+)\\\"").findAll(json).map{it.groupValues[1]}.toList()
    private fun jsonArray(values:List<String>)=values.joinToString(prefix="[",postfix="]"){"\"${esc(it)}\""}
    private fun jsonValue(v:Any?):String=when(v){null->"null";is Number,is Boolean->v.toString();is Map<*,*>->v.entries.joinToString(prefix="{",postfix="}"){"\"${esc(it.key.toString())}\":${jsonValue(it.value)}"};is Iterable<*>->v.joinToString(prefix="[",postfix="]"){jsonValue(it)};else->"\"${esc(v.toString())}\""}
    private fun extractJsonString(json:String,key:String):String?{val m=Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").find(json)?:return null;return m.groupValues[1].replace("\\n","\n").replace("\\\"","\"").replace("\\\\","\\")}
    private fun extractNestedText(json:String):String?=Regex("\\\"text\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").find(json)?.groupValues?.getOrNull(1)?.replace("\\n","\n")?.replace("\\\"","\"")
    private fun sanitize(v:String?)=v?.take(160)?.replace(Regex("(?i)(sk-[A-Za-z0-9_-]+|AIza[A-Za-z0-9_-]+)"),"[REDACTED]")?:"UNKNOWN_ERROR"
    private fun esc(v:String)=v.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")
    private fun encPath(v:String)=URLEncoder.encode(v,"UTF-8").replace("+","%20")
}
