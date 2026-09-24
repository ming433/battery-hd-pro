package com.batteryhd.app.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * 应用内语言。空标签表示跟随系统。
 *
 * 语言集合与电池类竞品（Battery Saver HD 等）公开的 13 种一致。
 * 由 AppCompat 持久化。印尼语资源在 values-in，标签用 id。
 */
object AppLanguage {

    const val SYSTEM = ""

    data class Choice(val tag: String, val nativeName: String)

    val choices: List<Choice> = listOf(
        Choice("en", "English"),
        Choice("fr", "Français"),
        Choice("de", "Deutsch"),
        Choice("hi", "हिन्दी"),
        Choice("hu", "Magyar"),
        Choice("id", "Bahasa Indonesia"),
        Choice("pl", "Polski"),
        Choice("pt", "Português"),
        Choice("ru", "Русский"),
        Choice("sk", "Slovenčina"),
        Choice("es", "Español"),
        Choice("th", "ไทย"),
        Choice("vi", "Tiếng Việt"),
    )

    fun currentTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return SYSTEM
        val language = locales[0]?.language ?: return SYSTEM
        return if (language == "in") "id" else language
    }

    fun nativeName(tag: String): String? = choices.firstOrNull { it.tag == tag }?.nativeName

    fun apply(tag: String) {
        val locales = if (tag.isBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}
