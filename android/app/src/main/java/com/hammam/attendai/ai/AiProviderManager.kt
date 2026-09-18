package com.hammam.attendai.ai

import com.hammam.attendai.data.local.HammamDatabase
import com.hammam.attendai.data.local.entity.AppSettingEntity
import com.hammam.attendai.security.KeystoreCipher
import com.hammam.attendai.security.SecureSecretStore
import com.hammam.attendai.sync.BackendClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.UUID

object AiProviderNames { const val OPENAI="OPENAI";const val GEMINI="GEMINI";const val CLAUDE="CLAUDE";val all=listOf(OPENAI,GEMINI,CLAUDE) }
enum class AiKeyMode{LOCAL_BYOK,BACKEND_MANAGED}
data class ProviderHealth(val provider:String,val configured:Boolean,val mode:AiKeyMode,val selectedModel:String?,val connectionStatus:String,val lastTest:Long?,val lastModelRefresh:Long?,val error:String?,val maskedKey:String?,val cachedModels:List<String>)

data class HttpReply(val code:Int,val body:String){val ok get()=code in 200..299}
class ProviderHttpClient{
    suspend fun request(method:String,url:String,headers:Map<String,String>,body:String?=null):HttpReply=withContext(Dispatchers.IO){
        try{val c=(URL(url).openConnection() as HttpURLConnection).apply{requestMethod=method;connectTimeout=10_000;readTimeout=30_000;headers.forEach{(k,v)->setRequestProperty(k,v)};if(body!=null){doOutput=true;setRequestProperty("Content-Type","application/json; charset=utf-8");outputStream.use{it.write(body.toByteArray(Charsets.UTF_8))}}};val code=c.responseCode;val text=runCatching{(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()}.getOrDefault("");HttpReply(code,text)}catch(e:Exception){HttpReply(599,e.javaClass.simpleName)}
    }
}

class AiProviderManager(
    private val db:HammamDatabase,
    private val cipher:KeystoreCipher,
    private val secrets:SecureSecretStore,
    private val backend:BackendClient,
    private val http:ProviderHttpClient=ProviderHttpClient(),
):AiProvider{
    private val dao=db.coreDao()
    private fun secretKey(provider:String)="ai.key.${provider.uppercase()}"
    private fun settingKey(provider:String,suffix:String)="ai.${provider.lowercase()}.$suffix"

    suspend fun setMode(provider:String,mode:AiKeyMode){put(provider,"mode",mode.name)}
    suspend fun setKey(provider:String,key:String?){secrets.put(secretKey(provider),key);put(provider,"last_error","")}
    suspend fun setSelectedModel(provider:String,model:String?){put(provider,"model",model.orEmpty())}
    suspend fun setDefaultProvider(provider:String){putGlobal("default_provider",provider)}
    suspend fun setAssignment(kind:String,value:String){putGlobal("assignment.${kind.lowercase()}",value)}
    suspend fun assignment(kind:String)=getGlobal("assignment.${kind.lowercase()}")?.ifBlank{null}?:"USE_DEFAULT"
    suspend fun defaultProvider()=getGlobal("default_provider")?.takeIf{it in AiProviderNames.all}?:AiProviderNames.OPENAI

    suspend fun health(provider:String):ProviderHealth{
        val mode=runCatching{AiKeyMode.valueOf(get(provider,"mode")?:AiKeyMode.BACKEND_MANAGED.name)}.getOrDefault(AiKeyMode.BACKEND_MANAGED)
        val models=parseArray(get(provider,"models").orEmpty());val configured=if(mode==AiKeyMode.LOCAL_BYOK)secrets.configured(secretKey(provider)) else true
        return ProviderHealth(provider,configured,mode,get(provider,"model")?.ifBlank{null},get(provider,"status")?:"NOT_TESTED",get(provider,"last_test")?.toLongOrNull(),get(provider,"last_refresh")?.toLongOrNull(),get(provider,"last_error")?.ifBlank{null},if(mode==AiKeyMode.LOCAL_BYOK)secrets.maskedSuffix(secretKey(provider)) else null,models)
    }

    suspend fun refreshModels(provider:String):List<String>{
        val h=health(provider);val result=if(h.mode==AiKeyMode.LOCAL_BYOK)listDirect(provider,secrets.get(secretKey(provider))?:error("API_KEY_NOT_CONFIGURED")) else listViaBackend(provider)
        val models=result.distinct().sorted();val now=System.currentTimeMillis();put(provider,"models",jsonArray(models));put(provider,"last_refresh",now.toString());put(provider,"status","CONNECTED");put(provider,"last_error","");return models
    }
    suspend fun test(provider:String):Boolean=runCatching{refreshModels(provider);put(provider,"last_test",System.currentTimeMillis().toString());true}.getOrElse{put(provider,"status","ERROR");put(provider,"last_test",System.currentTimeMillis().toString());put(provider,"last_error",sanitize(it.message));false}

    override suspend fun summarizeGrounded(prompt:String,facts:Map<String,Any?>):String?{
        val provider=defaultProvider();val h=health(provider);val model=h.selectedModel?:return null
        return if(h.mode==AiKeyMode.BACKEND_MANAGED){
            val payload="{\"provider\":\"$provider\",\"model\":\"${esc(model)}\",\"prompt\":\"${esc(prompt)}\",\"facts\":${jsonValue(facts)}}";val r=backend.post("/api/v1/ai/providers/summarize",payload,"ai:${payload.hashCode()}");if(r.ok)extractJsonString(r.responseBody.orEmpty(),"provider_response") else null
        }else summarizeDirect(provider,model,secrets.get(secretKey(provider))?:return null,prompt,facts)
    }

    /** Optional vision extraction used by timetable import. Returns provider text only; approval remains local/human. */
    suspend fun extractTimetableVision(provider:String,mime:String,base64:String,prompt:String):String?{
        val h=health(provider);val model=h.selectedModel?:return null
        if(h.mode==AiKeyMode.BACKEND_MANAGED){val payload="{\"provider\":\"$provider\",\"model\":\"${esc(model)}\",\"mime\":\"${esc(mime)}\",\"image_base64\":\"$base64\",\"prompt\":\"${esc(prompt)}\"}";val r=backend.post("/api/v1/ai/providers/vision",payload,"vision:${payload.hashCode()}");return if(r.ok)extractJsonString(r.responseBody.orEmpty(),"provider_response") else null}
        val key=secrets.get(secretKey(provider))?:return null
        return visionDirect(provider,model,key,mime,base64,prompt)
    }

    private suspend fun listViaBackend(provider:String):List<String>{val payload="{\"provider\":\"$provider\"}";val r=backend.post("/api/v1/ai/providers/models",payload,"models:$provider:${System.currentTimeMillis()/300000}");if(!r.ok)error(r.error?:"MODEL_REFRESH_FAILED");return parseArrayField(r.responseBody.orEmpty(),"models")}
    private suspend fun listDirect(provider:String,key:String):List<String>=when(provider){
        AiProviderNames.OPENAI->{val r=http.request("GET","https://api.openai.com/v1/models",mapOf("Authorization" to "Bearer $key"));if(!r.ok)error("OPENAI_HTTP_${r.code}");parseObjectIds(r.body,"id").filter(::textModelCandidate)}
        AiProviderNames.GEMINI->{val r=http.request("GET","https://generativelanguage.googleapis.com/v1beta/models",mapOf("x-goog-api-key" to key));if(!r.ok)error("GEMINI_HTTP_${r.code}");parseGeminiModels(r.body)}
        AiProviderNames.CLAUDE->{val r=http.request("GET","https://api.anthropic.com/v1/models",mapOf("x-api-key" to key,"anthropic-version" to "2023-06-01"));if(!r.ok)error("CLAUDE_HTTP_${r.code}");parseObjectIds(r.body,"id")}
        else->emptyList()
    }

    private suspend fun summarizeDirect(provider:String,model:String,key:String,prompt:String,facts:Map<String,Any?>):String?{
        val grounded="Facts (authoritative): ${jsonValue(facts)}\nTask: $prompt\nNever invent numbers. If facts are insufficient, say so."
        return when(provider){
            AiProviderNames.OPENAI->{val body="{\"model\":\"${esc(model)}\",\"input\":\"${esc(grounded)}\"}";val r=http.request("POST","https://api.openai.com/v1/responses",mapOf("Authorization" to "Bearer $key"),body);if(r.ok)extractJsonString(r.body,"output_text")?:extractNestedText(r.body) else null}
            AiProviderNames.GEMINI->{val body="{\"contents\":[{\"parts\":[{\"text\":\"${esc(grounded)}\"}]}]}";val r=http.request("POST","https://generativelanguage.googleapis.com/v1beta/models/${encPath(model.removePrefix("models/"))}:generateContent",mapOf("x-goog-api-key" to key),body);if(r.ok)extractNestedText(r.body) else null}
            AiProviderNames.CLAUDE->{val body="{\"model\":\"${esc(model)}\",\"max_tokens\":1024,\"messages\":[{\"role\":\"user\",\"content\":\"${esc(grounded)}\"}]}";val r=http.request("POST","https://api.anthropic.com/v1/messages",mapOf("x-api-key" to key,"anthropic-version" to "2023-06-01"),body);if(r.ok)extractNestedText(r.body) else null}
            else->null
        }
    }
    private suspend fun visionDirect(provider:String,model:String,key:String,mime:String,b64:String,prompt:String):String?=when(provider){
        AiProviderNames.OPENAI->{val media=if(mime.equals("application/pdf",true))"{\"type\":\"input_file\",\"filename\":\"timetable.pdf\",\"file_data\":\"data:application/pdf;base64,$b64\"}" else "{\"type\":\"input_image\",\"image_url\":\"data:${esc(mime)};base64,$b64\"}";val body="{\"model\":\"${esc(model)}\",\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"${esc(prompt)}\"},$media]}]}";val r=http.request("POST","https://api.openai.com/v1/responses",mapOf("Authorization" to "Bearer $key"),body);if(r.ok)extractJsonString(r.body,"output_text")?:extractNestedText(r.body) else null}
        AiProviderNames.GEMINI->{val body="{\"contents\":[{\"parts\":[{\"text\":\"${esc(prompt)}\"},{\"inline_data\":{\"mime_type\":\"${esc(mime)}\",\"data\":\"$b64\"}}]}]}";val r=http.request("POST","https://generativelanguage.googleapis.com/v1beta/models/${encPath(model.removePrefix("models/"))}:generateContent",mapOf("x-goog-api-key" to key),body);if(r.ok)extractNestedText(r.body) else null}
        AiProviderNames.CLAUDE->{val mediaType=if(mime.equals("application/pdf",true))"document" else "image";val body="{\"model\":\"${esc(model)}\",\"max_tokens\":2048,\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"$mediaType\",\"source\":{\"type\":\"base64\",\"media_type\":\"${esc(mime)}\",\"data\":\"$b64\"}},{\"type\":\"text\",\"text\":\"${esc(prompt)}\"}]}]}";val r=http.request("POST","https://api.anthropic.com/v1/messages",mapOf("x-api-key" to key,"anthropic-version" to "2023-06-01"),body);if(r.ok)extractNestedText(r.body) else null}
        else->null
    }

    private suspend fun put(provider:String,suffix:String,value:String){dao.upsertSetting(AppSettingEntity(settingKey(provider,suffix),cipher.encrypt(value),System.currentTimeMillis()))}
    private suspend fun get(provider:String,suffix:String):String?=dao.getSetting(settingKey(provider,suffix))?.valueCiphertext?.let{runCatching{cipher.decrypt(it)}.getOrNull()}
    private suspend fun putGlobal(suffix:String,value:String){dao.upsertSetting(AppSettingEntity("ai.global.$suffix",cipher.encrypt(value),System.currentTimeMillis()))}
    private suspend fun getGlobal(suffix:String):String?=dao.getSetting("ai.global.$suffix")?.valueCiphertext?.let{runCatching{cipher.decrypt(it)}.getOrNull()}
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
