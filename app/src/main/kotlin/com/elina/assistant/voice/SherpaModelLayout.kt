package com.elina.assistant.voice

import android.content.Context
import java.io.File

/**
 * Convention for where on-device ASR/TTS model files live.
 *
 * Layout (under app's external-files dir, so users / setup scripts can adb-push
 * without root):
 *
 *   <files>/models/
 *     ├── asr/
 *     │   ├── model.onnx              · paraformer (or zipformer encoder/decoder/joiner)
 *     │   ├── tokens.txt
 *     │   └── (optional) lexicon.txt
 *     └── tts/
 *         ├── model.onnx              · vits .onnx
 *         ├── tokens.txt
 *         ├── lexicon.txt             · for VITS Chinese
 *         └── espeak-ng-data/         · phoneme data dir (optional)
 *
 * Helper: [SherpaModelLayout] returns paths and quick existence checks.
 */
object SherpaModelLayout {

    fun root(context: Context): File =
        File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    fun asrDir(context: Context): File = File(root(context), "asr").apply { mkdirs() }
    fun ttsDir(context: Context): File = File(root(context), "tts").apply { mkdirs() }

    fun asrAvailable(context: Context): Boolean {
        val dir = asrDir(context)
        return File(dir, "tokens.txt").exists() &&
            (File(dir, "model.onnx").exists() ||
                (File(dir, "encoder.onnx").exists() &&
                    File(dir, "decoder.onnx").exists() &&
                    File(dir, "joiner.onnx").exists()))
    }

    fun ttsAvailable(context: Context): Boolean {
        val dir = ttsDir(context)
        return File(dir, "model.onnx").exists() &&
            File(dir, "tokens.txt").exists()
    }
}
