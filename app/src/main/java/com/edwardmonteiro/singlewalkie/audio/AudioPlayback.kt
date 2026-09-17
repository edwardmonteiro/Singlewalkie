package com.edwardmonteiro.singlewalkie.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max

class AudioPlayback {
    private val track: AudioTrack

    init {
        val minBuffer = AudioTrack.getMinBufferSize(
            OpusCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OpusCodec.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuffer, OpusCodec.FRAME_SAMPLES * 2 * 6))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
    }

    fun write(pcm: ShortArray) {
        if (pcm.isEmpty()) return
        track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
    }

    fun close() {
        try {
            track.stop()
        } catch (_: Throwable) {
        }
        track.release()
    }
}
