package com.hammam.attendai

import com.hammam.attendai.data.local.entity.AttendanceRecordEntity
import com.hammam.attendai.data.local.entity.LectureEntity
import com.hammam.attendai.data.repository.AttendanceLifecycleRules
import com.hammam.attendai.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class AttendanceLifecycleRulesTest {
    private fun lecture(status:LectureStatus=LectureStatus.SCHEDULED,start:Long=1_000,end:Long=2_000)=LectureEntity(
        "l","s","t","sem","g",start,end,null,null,null,status,"{}",0,0,1
    )
    private fun record(status:FinalAttendanceStatus)=AttendanceRecordEntity(
        "r","l","st",null,null,0,60,0.0,0,0,0.0,status,ApprovalStatus.DRAFT,PresenceSource.MANUAL,null,0,0,1
    )

    @Test fun doubleStartIsRejectedWhenActiveSessionExists(){
        assertFalse(AttendanceLifecycleRules.canCreateSession(true))
        assertTrue(AttendanceLifecycleRules.canCreateSession(false))
    }

    @Test fun startWindowRejectsCompletedAndOutsideTime(){
        assertFalse(AttendanceLifecycleRules.isStartableLecture(lecture(LectureStatus.COMPLETED),1_500))
        assertFalse(AttendanceLifecycleRules.isStartableLecture(lecture(),999))
        assertFalse(AttendanceLifecycleRules.isStartableLecture(lecture(),2_000))
        assertTrue(AttendanceLifecycleRules.isStartableLecture(lecture(),1_500))
    }

    @Test fun invalidDurationCannotStart(){
        assertFalse(AttendanceLifecycleRules.isStartableLecture(lecture(start=2_000,end=2_000),2_000))
    }

    @Test fun manualReviewBlocksApproval(){
        assertFalse(AttendanceLifecycleRules.recordsReadyForApproval(listOf(record(FinalAttendanceStatus.MANUAL_REVIEW))))
        assertTrue(AttendanceLifecycleRules.recordsReadyForApproval(listOf(record(FinalAttendanceStatus.PRESENT))))
    }

    @Test fun emptyAttendanceCannotBeApproved(){
        assertFalse(AttendanceLifecycleRules.recordsReadyForApproval(emptyList()))
    }

    @Test fun endAlwaysTransitionsToNeedsReview(){
        assertEquals(LectureStatus.NEEDS_REVIEW,AttendanceLifecycleRules.endLectureStatus())
    }

    @Test fun approvalAndFreezeTransitionsAreExplicit(){
        assertEquals(LectureStatus.COMPLETED,AttendanceLifecycleRules.approvedLectureStatus(false))
        assertEquals(LectureStatus.FROZEN,AttendanceLifecycleRules.approvedLectureStatus(true))
    }

    @Test fun invalidPolicySnapshotRequiresManualReview(){
        assertTrue(AttendanceLifecycleRules.requiresManualReview(false,false))
        assertTrue(AttendanceLifecycleRules.requiresManualReview(true,true))
        assertFalse(AttendanceLifecycleRules.requiresManualReview(true,false))
    }
}
