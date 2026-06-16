package com.urik.keyboard.service

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File

/** One pickable font. [fileName] = "" (system default), [MONOSPACE] sentinel, or an imported file name. */
data class FontOption(val displayName: String, val fileName: String)

/**
 * Keyboard label fonts (mirrors the sister repos' font system, in spirit): the built-in System / Monospace
 * families plus any `.ttf`/`.otf` the user imports via the document picker into the app's private fonts dir.
 * Typefaces are loaded by file and cached. A bad/missing file silently falls back to the default.
 */
object KeyboardFonts {
    const val SYSTEM = ""
    const val MONOSPACE = "@monospace"
    private val EXTENSIONS = setOf("ttf", "otf")

    private val cache = HashMap<String, Typeface>()

    fun fontsDir(context: Context): File = File(context.filesDir, "fonts").apply { if (!exists()) mkdirs() }

    /** System + Monospace + every imported font (sorted by name). */
    fun availableFonts(context: Context): List<FontOption> {
        val options = mutableListOf(
            FontOption(context.getString(com.urik.keyboard.R.string.font_system_default), SYSTEM),
            FontOption(context.getString(com.urik.keyboard.R.string.font_monospace), MONOSPACE)
        )
        fontsDir(context).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { options.add(FontOption(it.nameWithoutExtension, it.name)) }
        return options
    }

    fun displayName(context: Context, family: String): String = when {
        family.isEmpty() -> context.getString(com.urik.keyboard.R.string.font_system_default)
        family == MONOSPACE -> context.getString(com.urik.keyboard.R.string.font_monospace)
        else -> File(family).nameWithoutExtension
    }

    /** The base [Typeface] for a stored family value (cached). */
    fun typeface(context: Context, family: String): Typeface = when {
        family.isEmpty() -> Typeface.DEFAULT
        family == MONOSPACE -> Typeface.MONOSPACE
        else -> cache.getOrPut(family) {
            try {
                Typeface.createFromFile(File(fontsDir(context), family))
            } catch (e: Exception) {
                Typeface.DEFAULT
            }
        }
    }

    /** The family combined with bold (used by the renderer). */
    fun typeface(context: Context, family: String, bold: Boolean): Typeface {
        val base = typeface(context, family)
        return if (bold) Typeface.create(base, Typeface.BOLD) else base
    }

    /** The family combined with a numeric weight 100..900 (0 / null = the family's own weight). */
    fun weightedTypeface(context: Context, family: String, weight: Int?): Typeface {
        val base = typeface(context, family)
        if (weight == null || weight <= 0) return base
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, weight.coerceIn(1, 1000), false)
        } else {
            Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /** Copy a picked font file into the private fonts dir; returns its file name, or null on failure. */
    fun importFont(context: Context, uri: Uri): String? {
        val name = fileName(context, uri) ?: return null
        if (name.substringAfterLast('.', "").lowercase() !in EXTENSIONS) return null
        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            File(fontsDir(context), name).writeBytes(bytes)
            cache.remove(name)
            name
        } catch (e: Exception) {
            null
        }
    }

    private fun fileName(context: Context, uri: Uri): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // best-effort display name from the content provider
        }
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}
