package com.hammam.attendai

import com.hammam.attendai.data.repository.LectureSchedulerRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LectureMaterializationIdentityTest {
    @Test fun retryUsesSameLectureIdentity() {
        val first=LectureSchedulerRepository.lectureId("schedule-1","subject-1","group-1",1_700_000_000_000)
        val retry=LectureSchedulerRepository.lectureId("schedule-1","subject-1","group-1",1_700_000_000_000)
        assertEquals(first,retry)
    }

    @Test fun replacementScheduleDoesNotCollideWithCancelledPreviousLecture() {
        val old=LectureSchedulerRepository.lectureId("schedule-old","subject-1","group-1",1_700_000_000_000)
        val replacement=LectureSchedulerRepository.lectureId("schedule-new","subject-1","group-1",1_700_000_000_000)
        assertNotEquals(old,replacement)
    }

    @Test fun retryAfterPartialMaterializationSkipsExistingAndCreatesMissing() {
        org.junit.Assert.assertFalse(LectureSchedulerRepository.shouldInsert(identityExists=true,nonCancelledAtSlot=1))
        org.junit.Assert.assertTrue(LectureSchedulerRepository.shouldInsert(identityExists=false,nonCancelledAtSlot=0))
    }

    @Test fun replacementCanFillSlotAfterPreviousScheduleWasCancelled() {
        org.junit.Assert.assertTrue(LectureSchedulerRepository.shouldInsert(identityExists=false,nonCancelledAtSlot=0))
    }

    @Test fun distinctLectureSlotHasDistinctIdentity() {
        val first=LectureSchedulerRepository.lectureId("schedule-1","subject-1","group-1",1_700_000_000_000)
        val second=LectureSchedulerRepository.lectureId("schedule-1","subject-1","group-1",1_700_003_600_000)
        assertNotEquals(first,second)
    }
}
