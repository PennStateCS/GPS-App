package com.example.surveyingapp.ui.openinar

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Step 1 of the model-rendering pipeline.
 *
 * Manages GLB file validation and load-state caching so the AR renderer always
 * knows the status of each assigned model.  The [draw] method currently delegates
 * to a plain GLES30 cube (via the `fallback` lambda) and visually encodes load
 * state through colour:
 *
 *   • Grey  — model is being validated / not yet requested
 *   • Cyan  — model file is valid and ready  (will render the actual GLB in Phase 2)
 *   • Red   — file missing or invalid GLB magic bytes
 *
 * Phase 2 (Filament integration) will replace the `fallback` call in the `LOADED`
 * branch with a proper GLB geometry upload + Filament render pass.
 */
class ArModelRenderer(private val scope: CoroutineScope) {

    enum class LoadState { IDLE, LOADING, LOADED, FAILED }

    private data class CacheEntry(val state: LoadState, val error: String? = null)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    // -------------------------------------------------------------------------
    // Public API

    /**
     * Begin background validation of the GLB at [filePath].
     * Idempotent — duplicate calls for the same path are no-ops.
     *
     * Call this as soon as you know a coordinate has a model assigned (e.g. when
     * rebuilding geo-anchors) so validation completes before the anchor
     * is first rendered.
     */
    fun preload(filePath: String) {
        if (cache.containsKey(filePath)) return
        cache[filePath] = CacheEntry(LoadState.LOADING)
        scope.launch(Dispatchers.IO) {
            try {
                val file = File(filePath)
                require(file.exists()) { "File not found: $filePath" }
                require(file.length() >= 12L) { "File too small to be a valid GLB" }

                // GLB magic: little-endian 0x46546C67 ("glTF")
                val header = ByteArray(4)
                FileInputStream(file).use { it.read(header) }
                val magic = (header[0].toInt() and 0xFF) or
                        ((header[1].toInt() and 0xFF) shl 8) or
                        ((header[2].toInt() and 0xFF) shl 16) or
                        ((header[3].toInt() and 0xFF) shl 24)
                require(magic == 0x46546C67.toInt()) {
                    "Not a valid GLB (magic=0x${magic.toString(16)})"
                }

                cache[filePath] = CacheEntry(LoadState.LOADED)
                Log.d(TAG, "GLB validated: $filePath")
            } catch (e: Exception) {
                cache[filePath] = CacheEntry(LoadState.FAILED, e.message)
                Log.e(TAG, "GLB validation failed for $filePath — ${e.message}")
            }
        }
    }

    /** Returns the current [LoadState] for [filePath]. */
    fun getState(filePath: String): LoadState = cache[filePath]?.state ?: LoadState.IDLE

    /**
     * Render a model-linked anchor from the GL thread.
     *
     * @param mvp      Pre-multiplied model-view-projection matrix for this anchor.
     * @param filePath Absolute path to the GLB file.
     * @param fallback GLES30 cube renderer used until Filament integration is complete.
     *                 Receives (mvp, r, g, b, a).
     */
    fun draw(
        mvp: FloatArray,
        filePath: String,
        fallback: (mvp: FloatArray, r: Float, g: Float, b: Float, a: Float) -> Unit
    ) {
        when (getState(filePath)) {
            LoadState.LOADED -> {
                // TODO Phase 2: render actual GLB geometry via Filament engine.
                fallback(mvp, 0.0f, 0.9f, 1.0f, 1.0f)   // cyan = model ready
            }
            LoadState.LOADING -> {
                fallback(mvp, 0.6f, 0.6f, 0.6f, 1.0f)   // grey = loading
            }
            LoadState.FAILED -> {
                fallback(mvp, 1.0f, 0.2f, 0.2f, 1.0f)   // red = error
            }
            LoadState.IDLE -> {
                preload(filePath)                          // kick off validation lazily
                fallback(mvp, 0.6f, 0.6f, 0.6f, 1.0f)
            }
        }
    }

    /** Release all cached state (call from `onDestroyView`). */
    fun clear() = cache.clear()

    companion object {
        private const val TAG = "ArModelRenderer"
    }
}

