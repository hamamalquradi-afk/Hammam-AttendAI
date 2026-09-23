package com.hammam.attendai.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.hammam.attendai.HammamAttendAiApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AiAssistantViewModel(app:Application,private val savedStateHandle:SavedStateHandle):AndroidViewModel(app){
    private val container=(app as HammamAttendAiApplication).container
    private val _state=MutableStateFlow(AiAssistantUiState(input=savedStateHandle[INPUT_KEY]?:savedStateHandle[LEGACY_QUERY_KEY]?:""))
    val state:StateFlow<AiAssistantUiState> = _state.asStateFlow()

    init{
        viewModelScope.launch{
            container.aiProviderManager.revision.collect{refreshProviderIndicator()}
        }
    }

    fun setInput(value:String){
        savedStateHandle[INPUT_KEY]=value
        _state.value=_state.value.copy(input=value)
    }

    fun ask(){
        val now=System.currentTimeMillis()
        val started=AssistantChatRules.beginRequest(_state.value,newId("user"),now)?:return
        _state.value=started.first
        savedStateHandle[INPUT_KEY]=""
        viewModelScope.launch{performLocalRequest(started.second)}
    }

    fun retry(messageId:String){
        val started=AssistantChatRules.beginRetry(_state.value,messageId)?:return
        _state.value=started.first
        viewModelScope.launch{performLocalRequest(started.second)}
    }

    fun clearConversation(){
        if(_state.value.loading)return
        _state.value=AssistantChatRules.clear(_state.value)
        savedStateHandle[INPUT_KEY]=""
    }

    /** Cloud summarization remains explicit and only receives already-authorized grounded facts. */
    fun summarizeWithCloud(messageId:String){
        if(_state.value.loading)return
        val message=_state.value.messages.firstOrNull{it.id==messageId&&it.role==AssistantMessageRole.ASSISTANT}?:return
        val answer=message.answer?:return
        if(answer.groundedFacts.isEmpty())return
        val query=message.sourceQuery.orEmpty()
        _state.value=_state.value.copy(loading=true)
        viewModelScope.launch{
            try{
                val result=container.aiProviderManager.summarizeGroundedResult(query,answer.groundedFacts)
                _state.value=if(result.ok){
                    val reply=AssistantChatMessage(newId("cloud"),AssistantMessageRole.ASSISTANT,text=result.text,sourceQuery=query,timestamp=System.currentTimeMillis())
                    _state.value.copy(messages=_state.value.messages+reply,loading=false)
                }else{
                    val error=AssistantChatMessage(newId("error"),AssistantMessageRole.ERROR,errorCode=result.errorCode?:"CLOUD_AI_FAILED",timestamp=System.currentTimeMillis())
                    AssistantChatRules.fail(_state.value,error)
                }
            }catch(e:CancellationException){throw e}
            catch(_:Exception){
                val error=AssistantChatMessage(newId("error"),AssistantMessageRole.ERROR,errorCode="CLOUD_AI_FAILED",timestamp=System.currentTimeMillis())
                _state.value=AssistantChatRules.fail(_state.value,error)
            }
            refreshProviderIndicator()
        }
    }

    private suspend fun performLocalRequest(query:String){
        try{
            val answer=container.offlineAssistant.answer(query)
            val reply=AssistantChatMessage(newId("assistant"),AssistantMessageRole.ASSISTANT,answer=answer,sourceQuery=query,timestamp=System.currentTimeMillis())
            _state.value=AssistantChatRules.finishLocal(_state.value,reply)
        }catch(e:CancellationException){throw e}
        catch(_:Exception){
            val error=AssistantChatMessage(newId("error"),AssistantMessageRole.ERROR,errorCode="LOCAL_QUERY_FAILED",sourceQuery=query,timestamp=System.currentTimeMillis())
            _state.value=AssistantChatRules.fail(_state.value,error)
        }
    }

    private suspend fun refreshProviderIndicator(){
        val provider=container.aiProviderManager.defaultProvider()
        val h=container.aiProviderManager.health(provider)
        _state.value=_state.value.copy(provider=AssistantProviderIndicator(h.provider,h.selectedModel,h.connectionStatus,h.configured))
    }

    private fun newId(prefix:String)="$prefix-${UUID.randomUUID()}"

    private companion object{
        const val INPUT_KEY="assistant_input"
        const val LEGACY_QUERY_KEY="query"
    }
}
