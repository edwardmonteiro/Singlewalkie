package com.edwardmonteiro.singlewalkie.audio

import org.concentus.OpusApplication
import org.concentus.OpusDecoder
import org.concentus.OpusEncoder

class OpusCodec {
    private val encoder = OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP).apply {
        setBitrate(BITRATE)
        setComplexity(0)
    }
    private val decoder = OpusDecoder(SAMPLE_RATE, CHANNELS)

    fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == FRAME_SAMPLES) { "Expected $FRAME_SAMPLES samples, got ${pcm.size}" }
        val encoded = ByteArray(MAX_OPUS_PACKET)
        val bytes = encoder.encode(pcm, 0, FRAME_SAMPLES, encoded, 0, encoded.size)
        return encoded.copyOf(bytes)
    }

    fun decode(packet: ByteArray): ShortArray {
        val pcm = ShortArray(FRAME_SAMPLES)
        val samples = decoder.decode(packet, 0, packet.size, pcm, 0, FRAME_SAMPLES, false)
        return if (samples == pcm.size) pcm else pcm.copyOf(samples.coerceAtLeast(0))
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val FRAME_MS = 20
        const val FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000 // 320
        const val BITRATE = 24_000
        const val MAX_OPUS_PACKET = 1275
    }
}
