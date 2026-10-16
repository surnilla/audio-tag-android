package com.audiotag.app.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TtsConverter(private val context: Context) {

    companion object {
        const val TARGET_SAMPLE_RATE = 16000
        private const val TAG = "TtsConverter"
        private const val UTTERANCE_ID = "audio_tag_tts"
    }

    /**
     * Converts text to raw 16-bit signed PCM, 16 kHz, mono, little-endian.
     * Initialises TTS, synthesises to a temp WAV file, parses the WAV header,
     * then resamples / converts channels / bit-depth as required.
     */
    suspend fun textToPcm(text: String): ByteArray {
        val wavFile = File(context.cacheDir, "tts_output_${System.currentTimeMillis()}.wav")
        try {
            synthesizeToWav(text, wavFile)
            return wavToPcm(wavFile)
        } finally {
            wavFile.delete()
        }
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    private suspend fun synthesizeToWav(text: String, outputFile: File) {
        suspendCancellableCoroutine<Unit> { cont ->
            var tts: TextToSpeech? = null

            tts = TextToSpeech(context) { status ->
                if (status != TextToSpeech.SUCCESS) {
                    tts?.shutdown()
                    if (cont.isActive) cont.resumeWithException(
                        IllegalStateException("TTS initialisation failed with status $status")
                    )
                    return@TextToSpeech
                }

                val engine = tts ?: run {
                    if (cont.isActive) cont.resumeWithException(
                        IllegalStateException("TTS engine is null after init")
                    )
                    return@TextToSpeech
                }

                val locResult = engine.setLanguage(Locale.US)
                if (locResult == TextToSpeech.LANG_MISSING_DATA ||
                    locResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    engine.shutdown()
                    if (cont.isActive) cont.resumeWithException(
                        IllegalStateException("TTS language not supported: $locResult")
                    )
                    return@TextToSpeech
                }

                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {
                        engine.shutdown()
                        if (cont.isActive) cont.resume(Unit)
                    }

                    @Deprecated("Deprecated in API 21")
                    override fun onError(utteranceId: String?) {
                        engine.shutdown()
                        if (cont.isActive) cont.resumeWithException(
                            IllegalStateException("TTS synthesis error for utterance $utteranceId")
                        )
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        engine.shutdown()
                        if (cont.isActive) cont.resumeWithException(
                            IllegalStateException("TTS synthesis error $errorCode for utterance $utteranceId")
                        )
                    }
                })

                val params = android.os.Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
                }

                val result = engine.synthesizeToFile(text, params, outputFile, UTTERANCE_ID)
                if (result != TextToSpeech.SUCCESS) {
                    engine.shutdown()
                    if (cont.isActive) cont.resumeWithException(
                        IllegalStateException("synthesizeToFile() returned $result")
                    )
                }
            }

            cont.invokeOnCancellation {
                tts?.shutdown()
            }
        }
    }

    /**
     * Parses the WAV file, applying channel mixing, resampling, and bit-depth
     * conversion to produce 16-bit mono 16 kHz PCM.
     */
    private fun wavToPcm(wavFile: File): ByteArray {
        val bytes = wavFile.readBytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Validate RIFF header
        val riff = String(bytes, 0, 4)
        require(riff == "RIFF") { "Not a RIFF file: $riff" }
        // Skip file size (bytes 4-7)
        val wave = String(bytes, 8, 4)
        require(wave == "WAVE") { "Not a WAVE file: $wave" }

        // Read fmt chunk fields from their fixed offsets within the fmt chunk.
        // We scan for the "fmt " chunk to be safe.
        val fmtOffset = findChunk(bytes, "fmt ")
            ?: throw IllegalStateException("No 'fmt ' chunk found in WAV")

        buf.position(fmtOffset + 8) // skip "fmt " + chunk-size
        val audioFormat = buf.short.toInt() and 0xFFFF   // PCM = 1
        val numChannels = buf.short.toInt() and 0xFFFF
        val sampleRate = buf.int
        buf.int  // byteRate
        buf.short // blockAlign
        val bitsPerSample = buf.short.toInt() and 0xFFFF

        Log.d(TAG, "WAV: format=$audioFormat channels=$numChannels sampleRate=$sampleRate bits=$bitsPerSample")

        // Find the "data" chunk (don't assume offset 44)
        val dataOffset = findChunk(bytes, "data")
            ?: throw IllegalStateException("No 'data' chunk found in WAV")

        val dataSize = ByteBuffer.wrap(bytes, dataOffset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val dataStart = dataOffset + 8

        var samples = extractSamples(bytes, dataStart, dataSize, bitsPerSample)

        // Step 1: mix down stereo → mono
        if (numChannels == 2) {
            samples = mixStereoToMono(samples)
        } else if (numChannels != 1) {
            throw IllegalStateException("Unsupported channel count: $numChannels")
        }

        // Step 2: resample to TARGET_SAMPLE_RATE if needed
        if (sampleRate != TARGET_SAMPLE_RATE) {
            samples = linearResample(samples, sampleRate, TARGET_SAMPLE_RATE)
        }

        // Step 3: encode back to 16-bit little-endian PCM bytes
        val output = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            output.putShort(s.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
        return output.array()
    }

    /**
     * Scans the raw bytes for a RIFF chunk with the given 4-char ID and returns
     * the byte offset of the chunk header (i.e., where the 4-char ID starts).
     * Returns null if not found.
     */
    private fun findChunk(data: ByteArray, chunkId: String): Int? {
        val id = chunkId.toByteArray(Charsets.US_ASCII)
        // Start after the 12-byte RIFF/WAVE header
        var offset = 12
        while (offset + 8 <= data.size) {
            val match = id.indices.all { data[offset + it] == id[it] }
            if (match) return offset
            val chunkSize = ByteBuffer.wrap(data, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            // Move past this chunk; align to word boundary
            val next = offset + 8 + chunkSize
            offset = if (next % 2 != 0) next + 1 else next
        }
        return null
    }

    /**
     * Converts raw PCM bytes to a flat IntArray of samples, handling 8-bit and 16-bit.
     */
    private fun extractSamples(data: ByteArray, start: Int, size: Int, bitsPerSample: Int): IntArray {
        return when (bitsPerSample) {
            8 -> {
                // 8-bit WAV is unsigned; convert to 16-bit signed
                IntArray(size) { i ->
                    ((data[start + i].toInt() and 0xFF) - 128) * 256
                }
            }
            16 -> {
                val count = size / 2
                val buf = ByteBuffer.wrap(data, start, size).order(ByteOrder.LITTLE_ENDIAN)
                IntArray(count) { buf.short.toInt() }
            }
            else -> throw IllegalArgumentException("Unsupported bitsPerSample: $bitsPerSample")
        }
    }

    /**
     * Mixes stereo interleaved samples [L, R, L, R, …] down to mono by averaging.
     */
    private fun mixStereoToMono(stereo: IntArray): IntArray {
        val mono = IntArray(stereo.size / 2)
        for (i in mono.indices) {
            mono[i] = (stereo[i * 2] + stereo[i * 2 + 1]) / 2
        }
        return mono
    }

    /**
     * Linear interpolation resampler.
     */
    private fun linearResample(input: IntArray, fromRate: Int, toRate: Int): IntArray {
        if (fromRate == toRate) return input
        val outputLen = (input.size.toLong() * toRate / fromRate).toInt()
        val output = IntArray(outputLen)
        for (i in output.indices) {
            val srcPos = i.toDouble() * fromRate / toRate
            val lo = srcPos.toInt().coerceIn(0, input.size - 1)
            val hi = (lo + 1).coerceIn(0, input.size - 1)
            val frac = srcPos - lo
            output[i] = (input[lo] * (1.0 - frac) + input[hi] * frac).toInt()
        }
        return output
    }
}
