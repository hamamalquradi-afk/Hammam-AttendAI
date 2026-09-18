package com.hammam.attendai.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.hammam.attendai.HammamAttendAiApplication
import com.hammam.attendai.ai.AssistantAnswer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AiAssistantUiState(
    val query:String="",
    val answer:AssistantAnswer?=null,
    val loading:Boolean=false,
    val error:String?=null,
    val cloudSummary:String?=null,
)

class AiAssistantViewModel(app:Application,private val savedStateHandle:SavedStateHandle):AndroidViewModel(app){
    private val container=(app as HammamAttendAiApplication).container
    private val _state=MutableStateFlow(AiAssistantUiState(query=savedStateHandle["query"]?:""))
    val state:StateFlow<AiAssistantUiState> = _state.asStateFlow()

    fun setQuery(value:String){savedStateHandle["query"]=value;_state.value=_state.value.copy(query=value,error=null)}

    fun ask(){
        val query=_state.value.query.trim()
        if(query.isBlank())return
        viewModelScope.launch{
            _state.value=_state.value.copy(loading=true,error=null,cloudSummary=null)
            runCatching{container.offlineAssistant.answer(query)}
                .onSuccess{answer->_state.value=_state.value.copy(answer=answer,loading=false)}
                .onFailure{e->_state.value=_state.value.copy(loading=false,error=e.message?:"LOCAL_QUERY_FAILED")}
        }
    }

    /** Cloud summarization is explicit and grounded only in facts already returned by local tools. */
    fun summarizeWithCloud(){
        val current=_state.value
        val answer=current.answer ?: return
        if(answer.groundedFacts.isEmpty())return
        viewModelScope.launch{
            _state.value=_state.value.copy(loading=true,error=null)
            runCatching{container.aiProvider.summarizeGrounded(current.query,answer.groundedFacts)}
                .onSuccess{summary->_state.value=_state.value.copy(loading=false,cloudSummary=summary,error=if(summary==null)"CLOUD_AI_NOT_CONFIGURED" else null)}
                .onFailure{e->_state.value=_state.value.copy(loading=false,error=e.message?:"CLOUD_AI_FAILED")}
        }
    }
}
