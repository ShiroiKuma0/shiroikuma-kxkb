package com.urik.keyboard.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sensible standard keyboards for languages that ship a dictionary but no layout yet. The Library's
 * "Add language" flow builds one on the shared 10c frame (numbers row + predictive bottom bar from
 * `en_qwerty_5r10c`) with the language's conventional letter rows, and stores it as a CUSTOM layout —
 * fully editable/deletable like any other custom layout.
 */
object StandardLayouts {
    data class Spec(
        val nativeName: String,
        val layoutName: String,
        val script: String,
        val rows: List<String>,
        val rtl: Boolean = false
    )

    val SPECS: Map<String, Spec> = linkedMapOf(
        "de" to Spec("Deutsch", "QWERTZ", "Latn", listOf("qwertzuiopü", "asdfghjklöä", "yxcvbnmß")),
        "sk" to Spec("slovenčina", "QWERTZ", "Latn", listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")),
        "fr" to Spec("français", "AZERTY", "Latn", listOf("azertyuiop", "qsdfghjklm", "wxcvbn")),
        "es" to Spec("español", "QWERTY", "Latn", listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm")),
        "it" to Spec("italiano", "QWERTY", "Latn", listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")),
        "nl" to Spec("Nederlands", "QWERTY", "Latn", listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")),
        "pl" to Spec("polski", "QWERTY", "Latn", listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")),
        "pt" to Spec("português", "QWERTY", "Latn", listOf("qwertyuiop", "asdfghjklç", "zxcvbnm")),
        "ca" to Spec("català", "QWERTY", "Latn", listOf("qwertyuiop", "asdfghjklç", "zxcvbnm")),
        "sv" to Spec("svenska", "QWERTY", "Latn", listOf("qwertyuiopå", "asdfghjklöä", "zxcvbnm")),
        "el" to Spec("ελληνικά", "ΕΡΤΥ", "Grek", listOf("ςερτυθιοπ", "ασδφγηξκλ", "ζχψωβνμ")),
        "uk" to Spec("українська", "ЙЦУКЕН", "Cyrl", listOf("йцукенгшщзхї", "фівапролджє", "ячсмитьбю")),
        "bg" to Spec("български", "фонетична", "Cyrl", listOf("явертъуиопю", "асдфгхйклщш", "зьцжбнмч")),
        "ar" to Spec("العربية", "عربي", "Arab", listOf("ضصثقفغعهخح", "شسيبلاتنمك", "ظطذدزروةىء"), rtl = true),
        "fa" to Spec("فارسی", "فارسی", "Arab", listOf("ضصثقفغعهخح", "شسیبلاتنمک", "ظطزرذدپوگچ"), rtl = true)
    )

    fun buildLayoutJson(context: Context, lang: String, spec: Spec): JSONObject {
        val frame = context.assets.open("layouts/en_qwerty_5r10c.json")
            .bufferedReader().use { it.readText() }
        val json = JSONObject(frame)
        json.put("locale", lang)
        json.put("script", spec.script)
        json.put("isRTL", spec.rtl)
        json.put("name", "kxkb $lang ${spec.layoutName} 5r10c")
        val letters = json.getJSONObject("modes").getJSONObject("letters").getJSONArray("rows")
        spec.rows.forEachIndexed { i, rowChars ->
            val row = JSONArray()
            rowChars.forEach { c ->
                row.put(JSONObject().put("type", "compass").put("char", c.toString()))
            }
            letters.put(i + 1, row)
        }
        return json
    }
}
