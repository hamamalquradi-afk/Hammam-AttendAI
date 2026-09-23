package com.hammam.attendai.ui

import com.hammam.attendai.ai.AssistantAnswer

enum class AssistantMessageRole{USER,ASSISTANT,SYSTEM,ERROR}

data class AssistantProviderIndicator(
    val providerId:String,
    val modelId:String?,
    val status:String,
    val configured:Boolean,
)

data class AssistantChatMessage(
    val id:String,
    val role:AssistantMessageRole,
    val text:String?=null,
    val answer:AssistantAnswer?=null,
    val errorCode:String?=null,
    val sourceQuery:String?=null,
    val timestamp:Long=System.currentTimeMillis(),
)

data class AiAssistantUiState(
    val input:String="",
    val messages:List<AssistantChatMessage> = emptyList(),
    val loading:Boolean=false,
    val provider:AssistantProviderIndicator?=null,
)

object AssistantChatRules{
    fun canSend(input:String,loading:Boolean)=input.trim().isNotEmpty()&&!loading
    fun beginRequest(state:AiAssistantUiState,messageId:String,now:Long):Pair<AiAssistantUiState,String>?{
        if(!canSend(state.input,state.loading))return null
        val query=state.input.trim()
        val user=AssistantChatMessage(messageId,AssistantMessageRole.USER,text=query,sourceQuery=query,timestamp=now)
        return state.copy(input="",messages=state.messages+user,loading=true) to query
    }
    fun finishLocal(state:AiAssistantUiState,message:AssistantChatMessage)=state.copy(messages=state.messages+message,loading=false)
    fun fail(state:AiAssistantUiState,message:AssistantChatMessage)=state.copy(messages=state.messages+message,loading=false)
    fun beginRetry(state:AiAssistantUiState,errorMessageId:String):Pair<AiAssistantUiState,String>?{
        if(state.loading)return null
        val failed=state.messages.firstOrNull{it.id==errorMessageId&&it.role==AssistantMessageRole.ERROR}?:return null
        val query=failed.sourceQuery?.takeIf{it.isNotBlank()}?:return null
        return state.copy(messages=state.messages.filterNot{it.id==errorMessageId},loading=true) to query
    }
    fun clear(state:AiAssistantUiState)=state.copy(input="",messages=emptyList(),loading=false)
}
