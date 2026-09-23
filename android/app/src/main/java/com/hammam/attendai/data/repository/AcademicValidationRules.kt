package com.hammam.attendai.data.repository

object AcademicValidationRules {
    private val phone=Regex("[+0-9 -]{6,20}")
    private val email=Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    fun validateContact(phoneValue:String?,whatsapp:String?,emailValue:String?):String?{
        phoneValue?.trim()?.takeIf{it.isNotEmpty()}?.let{if(!phone.matches(it))return "INVALID_PHONE"}
        whatsapp?.trim()?.takeIf{it.isNotEmpty()}?.let{if(!phone.matches(it))return "INVALID_WHATSAPP"}
        emailValue?.trim()?.takeIf{it.isNotEmpty()}?.let{if(!email.matches(it))return "INVALID_EMAIL"}
        return null
    }
}
