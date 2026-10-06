package com.focusflow.i18n

enum class AppLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String,
    val flag: String
) {
    ENGLISH("en", "English", "English", "🇺🇸"),
    SPANISH("es", "Spanish", "Español", "🇪🇸"),
    PORTUGUESE_BRAZIL("pt-BR", "Portuguese (Brazil)", "Português (Brasil)", "🇧🇷"),
    CHINESE_SIMPLIFIED("zh", "Chinese (Simplified)", "中文（简体）", "🇨🇳"),
    JAPANESE("ja", "Japanese", "日本語", "🇯🇵"),
    KOREAN("ko", "Korean", "한국어", "🇰🇷"),
    GERMAN("de", "German", "Deutsch", "🇩🇪"),
    FRENCH("fr", "French", "Français", "🇫🇷");

    companion object {
        fun fromCode(code: String): AppLanguage =
            entries.firstOrNull { it.code == code } ?: ENGLISH
    }
}
