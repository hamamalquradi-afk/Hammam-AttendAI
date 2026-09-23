package com.hammam.attendai
import com.hammam.attendai.domain.attendance.*
import com.hammam.attendai.domain.model.*
import org.junit.Assert.*
import org.junit.Test
class PresenceStateMachineTest{
 @Test fun transientLossDoesNotLeave(){val s=PresenceStateMachine(120);val a=s.reduce(PresenceStateMachine.Snapshot(AttendanceState.PRESENT,10,null),PresenceEventType.LOST,20);assertEquals(AttendanceState.TEMPORARILY_MISSING,s.onTick(a,100).state)}
 @Test fun graceExpiryLeaves(){val s=PresenceStateMachine(120);val a=s.reduce(PresenceStateMachine.Snapshot(AttendanceState.PRESENT,10,null),PresenceEventType.LOST,20);assertEquals(AttendanceState.LEFT,s.onTick(a,141).state)}
 @Test fun returnUsesSameStateMachine(){val s=PresenceStateMachine(120);val a=PresenceStateMachine.Snapshot(AttendanceState.LEFT,10,20);assertEquals(AttendanceState.RETURNED,s.reduce(a,PresenceEventType.REDETECTED,200).state)}
}
