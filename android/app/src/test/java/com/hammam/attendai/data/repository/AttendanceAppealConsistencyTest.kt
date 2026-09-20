package com.hammam.attendai.data.repository

import com.hammam.attendai.domain.model.ApprovalStatus
import com.hammam.attendai.domain.model.LectureStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AttendanceAppealConsistencyTest {
    @Test fun changedApprovedRecordReturnsToDraft() {
        assertEquals(ApprovalStatus.DRAFT, AppealConsistencyRules.reviewApprovalStatus(true))
    }

    @Test fun acceptedChangeReopensCompletedLecture() {
        assertEquals(LectureStatus.NEEDS_REVIEW, AppealConsistencyRules.lectureStatusAfterAcceptedChange(LectureStatus.COMPLETED))
    }

    @Test fun acceptedChangeReopensFrozenLecture() {
        assertEquals(LectureStatus.NEEDS_REVIEW, AppealConsistencyRules.lectureStatusAfterAcceptedChange(LectureStatus.FROZEN))
    }

    @Test fun verifiedSecondsMatchesPercentage() {
        assertEquals(2700L, AppealConsistencyRules.verifiedSeconds(3600L, 0.75))
    }
}
