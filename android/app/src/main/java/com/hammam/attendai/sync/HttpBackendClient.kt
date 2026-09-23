package com.hammam.attendai.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

internal object BackendAuthRules{
    private val authErrors=setOf("AUTH_REQUIRED","AUTH_INVALID","AUTH_EXPIRED","AUTH_REVOKED","ACCOUNT_NOT_FOUND")
    fun authorizationHeader(token:String?):String?=token?.trim()?.takeIf{it.isNotBlank()}?.let{"Bearer $it"}
    fun authenticationRequired(error:String?):Boolean=error in authErrors
    fun provisioningRequired(error:String?):Boolean=error=="PROVISIONING_REQUIRED"
    fun identityRecoveryRequired(error:String?):Boolean=authenticationRequired(error)||provisioningRequired(error)||error=="MEMBERSHIP_REQUIRED"
}

internal object BackendSessionRules{
    fun replacementToken(issuedToken:String?):String?=issuedToken?.trim()?.takeIf{it.isNotBlank()}
    fun clearedToken():String?=null
}

class HttpBackendClient(private val baseUrl:suspend ()->String?,private val authToken:suspend ()->String?={null}):BackendClient{
    override suspend fun post(path:String,payload:String,idempotencyKey:String):BackendResult=request(path,payload,idempotencyKey,authToken())
    override suspend fun postWithBearer(path:String,payload:String,idempotencyKey:String,bearerToken:String?):BackendResult=request(path,payload,idempotencyKey,bearerToken)

    private suspend fun request(path:String,payload:String,idempotencyKey:String,bearerToken:String?)=withContext(Dispatchers.IO){
        val base=baseUrl()?.trimEnd('/')?.takeIf{it.isNotBlank()} ?: return@withContext BackendResult(false,retryable=false,error="Backend not configured")
        if(!base.startsWith("https://")) return@withContext BackendResult(false,retryable=false,error="HTTPS_REQUIRED")
        try{
            val c=(URL(base+path).openConnection() as HttpURLConnection).apply{
                requestMethod="POST";connectTimeout=10_000;readTimeout=30_000;doOutput=true
                setRequestProperty("Content-Type","application/json; charset=utf-8")
                setRequestProperty("Idempotency-Key",idempotencyKey)
                BackendAuthRules.authorizationHeader(bearerToken)?.let{setRequestProperty("Authorization",it)}
            }
            c.outputStream.use{it.write(payload.ifBlank{"{}"}.toByteArray(Charsets.UTF_8))}
            val code=c.responseCode;val ok=code in 200..299
            val text=runCatching{(if(ok)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()}.getOrDefault("")
            val provider=Regex("\\\"provider_message_id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(text)?.groupValues?.getOrNull(1)
            val error=if(ok)null else Regex("\\\"error\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(text)?.groupValues?.getOrNull(1) ?: "HTTP $code"
            BackendResult(ok,providerMessageId=provider,retryable=code>=500||code==429||code==408,error=error,responseBody=text)
        }catch(e:CancellationException){throw e}
        catch(e:Exception){BackendResult(false,retryable=true,error=e.javaClass.simpleName)}
    }
}
