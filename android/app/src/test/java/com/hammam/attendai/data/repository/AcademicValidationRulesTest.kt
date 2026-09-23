package com.hammam.attendai.data.repository

import org.junit.Assert.*
import org.junit.Test

class AcademicValidationRulesTest {
    @Test fun validContactsPass(){assertNull(AcademicValidationRules.validateContact("+967 777111222","777111222","teacher@example.com"))}
    @Test fun invalidPhoneFails(){assertEquals("INVALID_PHONE",AcademicValidationRules.validateContact("abc",null,null))}
    @Test fun invalidWhatsappFails(){assertEquals("INVALID_WHATSAPP",AcademicValidationRules.validateContact(null,"12x",null))}
    @Test fun invalidEmailFails(){assertEquals("INVALID_EMAIL",AcademicValidationRules.validateContact(null,null,"bad-email"))}
}
