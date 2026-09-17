package com.edwardmonteiro.singlewalkie.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.max

class AudioCapture {
    @Volatile private var running = false
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(onFrame: (ShortArray) -> Unit) {
        if (running) return

        val frameBytes = OpusCodec.FRAME_SAMPLES * 2
        val minBuffer = AudioRecord.getMinBufferSize(
            OpusCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = max(minBuffer, frameBytes * 8)

        val activeRecorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OpusCodec.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()

        check(activeRecorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialize" }
        recorder = activeRecorder
        running = true
        activeRecorder.startRecording()

        thread = Thread({ captureLoop(activeRecorder, onFrame) }, "walkie-audio-capture").apply {
            isDaemon = true
            start()
        }
    }

    private fun captureLoop(activeRecorder: AudioRecord, onFrame: (ShortArray) -> Unit) {
        val frame = ShortArray(OpusCodec.FRAME_SAMPLES)
        var offset = 0
        while (running) {
            val read = try {
                activeRecorder.read(frame, offset, frame.size - offset, AudioRecord.READ_BLOCKING)
            } catch (_: Throwable) {
                break
            }
            if (read <= 0) continue
            offset += read
            if (offset == frame.size) {
                onFrame(frame.copyOf())
                offset = 0
            }
        }
    }

    fun stop() {
        running = false
        val activeRecorder = recorder
        try {
            if (activeRecorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) activeRecorder.stop()
        } catch (_: Throwable) {
        }
        thread?.join(300)
        thread = null
        try {
            activeRecorder?.release()
        } catch (_: Throwable) {
        }
        recorder = null
    }
}
