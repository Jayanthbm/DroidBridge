package com.droidbridge.experiment.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * P04: establish exactly when Android lets this app read the clipboard.
 *
 * The rule this exists to measure is `hasPrimaryClip()` returning false (or `primaryClip`
 * returning null) whenever the app is not the focused, visible app. Android 10+ gates clipboard
 * reads on focus; Android 12+ additionally shows a toast when an app reads the clipboard.
 *
 * Every result here is an observation, never a guess. A read that returns null is recorded as
 * null with the reason, because "the clipboard was empty" and "the OS refused" are different
 * findings and only one of them is a product problem.
 */
object ClipboardProbe {

    private const val TAG = "P04Clipboard"

    /**
     * `ClipDescription.EXTRA_IS_SENSITIVE` as a literal.
     *
     * The constant is API 33+, but the string is stable and a clip written on API 33+ can be read
     * on API 33+ only. Using the literal keeps the probe compiling on API 31 while still reading
     * the real flag wherever it exists.
     */
    private const val SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"

    /** What one read attempt observed. */
    data class ReadResult(
        val case: String,
        val hasPrimaryClip: Boolean,
        val mimeTypes: List<String>,
        val textLength: Int,
        val textPreview: String,
        val hasImage: Boolean,
        val hasUri: Boolean,
        val isSensitive: Boolean,
        val description: String,
        val error: String?,
    ) {
        /** One greppable line per observation, for the evidence log. */
        fun toLine(): String = buildString {
            append("case=\"$case\"")
            append(" hasPrimaryClip=$hasPrimaryClip")
            append(" mimes=$mimeTypes")
            append(" textLen=$textLength")
            append(" hasImage=$hasImage")
            append(" hasUri=$hasUri")
            append(" sensitive=$isSensitive")
            append(" desc=\"$description\"")
            // Escape everything that would break the one-line-per-observation contract. The
            // preview is arbitrary clipboard content, so a newline or a quote is expected, not
            // exceptional, and an unescaped one would corrupt the evidence log.
            if (textLength > 0) append(" preview=\"${textPreview.escapeForLog()}\"")
            if (error != null) append(" error=\"$error\"")
        }
    }

    /**
     * Reads the clipboard once and records everything observable about it.
     *
     * `ClipDescription.extras` carries `EXTRA_IS_SENSITIVE`, which an app sets to ask the system to
     * suppress the clipboard preview. Whether the flag also blocks a read is exactly what P04
     * needs to know, so it is recorded rather than assumed.
     */
    fun read(context: Context, case: String): ReadResult {
        val manager = context.getSystemService(ClipboardManager::class.java)
            ?: return ReadResult(
                case = case,
                hasPrimaryClip = false,
                mimeTypes = emptyList(),
                textLength = 0,
                textPreview = "",
                hasImage = false,
                hasUri = false,
                isSensitive = false,
                description = "",
                error = "ClipboardManager unavailable",
            )

        return try {
            val hasClip = manager.hasPrimaryClip()
            val clip: ClipData? = if (hasClip) manager.primaryClip else null
            val description = clip?.description

            val mimes = description?.mimeTypeCount?.let { count ->
                (0 until count).map { description.getMimeType(it) }
            } ?: emptyList()

            val text = if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(context)?.toString()
            } else {
                null
            }

            val hasImage = mimes.any { it.startsWith("image/") }
            val hasUri = mimes.any { it == "text/uri-list" }
            // EXTRA_IS_SENSITIVE is API 33+. On API 31/32 the flag does not exist, so it can be
            // neither set nor read, and the matrix row is not applicable rather than "false".
            val sensitive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                description?.extras?.getBoolean(SENSITIVE_EXTRA) == true
            } else {
                false
            }

            val result = ReadResult(
                case = case,
                hasPrimaryClip = hasClip,
                mimeTypes = mimes,
                textLength = text?.length ?: 0,
                textPreview = text?.take(60)?.replace("\n", "\\n") ?: "",
                hasImage = hasImage,
                hasUri = hasUri,
                isSensitive = sensitive,
                description = description?.label?.toString() ?: "",
                error = null,
            )
            Log.i(TAG, "READ ${result.toLine()}")
            result
        } catch (e: Exception) {
            // A SecurityException here is a finding, not a crash.
            val result = ReadResult(
                case = case,
                hasPrimaryClip = false,
                mimeTypes = emptyList(),
                textLength = 0,
                textPreview = "",
                hasImage = false,
                hasUri = false,
                isSensitive = false,
                description = "",
                error = "${e.javaClass.simpleName}: ${e.message}",
            )
            Log.i(TAG, "READ ${result.toLine()}")
            result
        }
    }

    /** Writes text so a read can be attempted against known content. */
    fun writeText(context: Context, text: String, label: String = "p04") {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
        Log.i(TAG, "WROTE label=$label len=${text.length}")
    }

    /**
     * Writes text marked sensitive, to see whether the flag blocks a read.
     *
     * On API 31/32 the flag does not exist, so this writes an ordinary clip and says so in the
     * log rather than pretending the sensitive case was exercised.
     */
    fun writeSensitiveText(context: Context, text: String) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            manager.setPrimaryClip(ClipData.newPlainText("p04-sensitive", text))
            Log.i(TAG, "WROTE sensitive=NOT_APPLICABLE api=${Build.VERSION.SDK_INT} len=${text.length}")
            return
        }
        val clip = ClipData.newPlainText("p04-sensitive", text)
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(SENSITIVE_EXTRA, true)
        }
        manager.setPrimaryClip(clip)
        Log.i(TAG, "WROTE sensitive=true len=${text.length}")
    }

    /**
     * Makes free text safe to embed in a single log line.
     *
     * Clipboard content is arbitrary: newlines, quotes and control characters all arrive in
     * normal use. Left raw, any of them would split or corrupt an evidence line.
     */
    private fun String.escapeForLog(): String = buildString {
        for (ch in this@escapeForLog) {
            when {
                ch == '\\' -> append("\\\\")
                ch == '"' -> append("\\\"")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch.code < 0x20 -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
    }

    /** Clears the clipboard so the empty case can be measured. */
    fun clear(context: Context) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        // minSdk is 31, so clearPrimaryClip (API 28) is always available.
        manager.clearPrimaryClip()
        Log.i(TAG, "CLEARED")
    }

    /**
     * Writes an image-only clip, to see whether a text read is refused by policy.
     *
     * `ClipData.newBitmap` does not exist. An image clip is a content URI whose MIME type is an
     * image type, which is also what a real app would put on the clipboard, so this is the honest
     * shape for the test rather than a synthetic one.
     */
    fun writeImageOnly(context: Context) {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return
        val file = java.io.File(context.cacheDir, "p04-image.png")
        val bitmap = androidx.core.graphics.createBitmap(8, 8)
        bitmap.eraseColor(android.graphics.Color.RED)
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.p04files",
            file,
        )
        // ClipData.newUri needs a resolver that can report the type.
        val clip = ClipData.newUri(context.contentResolver, "p04-image", uri)
        manager.setPrimaryClip(clip)
        Log.i(TAG, "WROTE image uri=$uri mime=${clip.description.getMimeType(0)}")
    }
}
