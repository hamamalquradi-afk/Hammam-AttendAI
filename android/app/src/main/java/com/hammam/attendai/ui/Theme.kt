package com.hammam.attendai.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import java.util.Locale

@Composable fun HammamTheme(mode:String="SYSTEM",content:@Composable()->Unit){
    val dark=when(mode.uppercase()){ "DARK"->true;"LIGHT"->false;else->isSystemInDarkTheme() }
    MaterialTheme(colorScheme=if(dark)darkColorScheme() else lightColorScheme(),content=content)
}

object AppLocaleController{
    fun apply(context:Context,language:String):Boolean{
        val tag=if(language.uppercase()=="EN")"en" else "ar"
        val current=context.resources.configuration.locales[0]?.language
        if(current==tag)return false
        val locale=Locale.forLanguageTag(tag);Locale.setDefault(locale)
        val config=Configuration(context.resources.configuration).apply{setLocale(locale);setLayoutDirection(locale)}
        @Suppress("DEPRECATION") context.resources.updateConfiguration(config,context.resources.displayMetrics)
        return true
    }
}
