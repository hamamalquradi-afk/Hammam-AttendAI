package com.hammam.attendai.ai

import com.hammam.attendai.ui.*
import org.junit.Assert.*
import org.junit.Test

class AssistantChatStateTest{
    @Test fun emptyConversationIsActuallyEmpty(){
        val state=AiAssistantUiState()
        assertTrue(state.messages.isEmpty())
        assertFalse(state.loading)
    }
    @Test fun duplicateSendIsBlockedWhileRequestIsInFlight(){
        assertFalse(AssistantChatRules.canSend("hello",true))
        assertNull(AssistantChatRules.beginRequest(AiAssistantUiState(input="second",loading=true),"u2",2L))
    }
    @Test fun sendAppendsUserAndClearsComposer(){
        val (state,query)=AssistantChatRules.beginRequest(AiAssistantUiState(input="  hello  "),"u1",1L)!!
        assertEquals("hello",query)
        assertEquals("",state.input)
        assertTrue(state.loading)
        assertEquals(AssistantMessageRole.USER,state.messages.single().role)
    }
    @Test fun failedRequestReturnsComposerToUsableStateAndKeepsRetryContext(){
        val started=AssistantChatRules.beginRequest(AiAssistantUiState(input="hello"),"u1",1L)!!.first
        val error=AssistantChatMessage("e1",AssistantMessageRole.ERROR,errorCode="LOCAL_QUERY_FAILED",sourceQuery="hello",timestamp=2L)
        val failed=AssistantChatRules.fail(started,error)
        assertFalse(failed.loading)
        val retry=AssistantChatRules.beginRetry(failed,"e1")
        assertNotNull(retry)
        assertEquals("hello",retry!!.second)
        assertTrue(retry.first.loading)
        assertTrue(retry.first.messages.none{it.id=="e1"})
    }
}
