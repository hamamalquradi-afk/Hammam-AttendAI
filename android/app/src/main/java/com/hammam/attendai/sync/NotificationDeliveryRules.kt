package com.hammam.attendai.sync

object NotificationDeliveryRules {
    fun normalizePhone(raw:String?):String? {
        val value=raw?.trim()?.takeIf{it.isNotEmpty()} ?: return null
        val plus=value.startsWith("+")
        val digits=value.filter{it.isDigit()}
        if(digits.length !in 7..15)return null
        return (if(plus) "+" else "")+digits
    }
    fun validEmail(raw:String?):String? {
        val value=raw?.trim()?.takeIf{it.isNotEmpty()} ?: return null
        if(value.length>254 || value.contains(' ') || value.count{it=='@'}!=1)return null
        val parts=value.split('@'); if(parts[0].isBlank() || parts[1].isBlank() || !parts[1].contains('.'))return null
        return value
    }
    fun retryable(result:BackendResult):Boolean = !result.ok && result.retryable
    fun permanent(result:BackendResult):Boolean = !result.ok && !result.retryable
    fun externalChannel(channel:String)=channel.uppercase() in setOf("WHATSAPP","EMAIL")
}
