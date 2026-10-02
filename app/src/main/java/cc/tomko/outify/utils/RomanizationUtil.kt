package cc.tomko.outify.utils

import android.icu.text.Transliterator
import android.os.Build

object RomanizationUtil {
    private val transliterator by lazy {
        Transliterator.getInstance("Any-Latin")
    }

    fun romanize(text: String): String {
        val hasNonLatin = text.any { c ->
            c.isLetter() && Character.UnicodeScript.of(c.code) != Character.UnicodeScript.LATIN
        }
        if (!hasNonLatin) return text
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            transliterator.transliterate(text)
        } else {
            text
        }
    }
}
