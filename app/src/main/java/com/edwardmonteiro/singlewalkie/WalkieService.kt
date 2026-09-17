package com.edwardmonteiro.singlewalkie

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.edwardmonteiro.singlewalkie.audio.AudioCapture
import com.edwardmonteiro.singlewalkie.audio.AudioPlayback
import com.edwardmonteiro.singlewalkie.audio.OpusCodec
import com.edwardmonteiro.singlewalkie.network.PacketType
import com.edwardmonteiro.singlewalkie.network.UdpTransport
import com.edwardmonteiro.singlewalkie.network.WalkiePacket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WalkieService : Service() {
    private var config: WalkieConfig = WalkieConfig("Walkie", "", WalkieConfig.DEFAULT_PORT)
    private var transport: UdpTransport? = null
    private var peerAddress: InetAddress? = null
    private val codec = OpusCodec()
    private val capture = AudioCapture()
    private val playback = AudioPlayback()
    private val audioSequence = AtomicInteger(0)
    private val controlSequence = AtomicInteger(0)
    private var lastRemoteAudioSequence: Int? = null
    @Volatile private var lastPeerSeenMs: Long = 0
    private var heartbeat: ScheduledExecutorService? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tone: ToneGenerator? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting mesh radio…"))

        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SingleWalkie:receiver").apply {
            setReferenceCounted(false)
            acquire(8 * 60 * 60 * 1000L)
        }

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        run { audioManager.isSpeakerphoneOn = true }

        tone = ToneGenerator(AudioManager.STREAM_MUSIC, 55)
        reloadConfig(restartTransport = true)
        startHeartbeat()
        WalkieRuntimeState.serviceRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_CONFIG -> reloadConfig(restartTransport = true)
            ACTION_PTT_START -> startTransmitting()
            ACTION_PTT_STOP -> stopTransmitting()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun reloadConfig(restartTransport: Boolean) {
        val previousPort = config.port
        config = WalkieConfig.load(this)
        WalkieRuntimeState.resetSessionStats()
        lastPeerSeenMs = 0
        lastRemoteAudioSequence = null

        peerAddress = if (config.peerHost.isBlank()) {
            null
        } else {
            try {
                InetAddress.getByName(config.peerHost).also {
                    WalkieRuntimeState.resolvedPeer = it.hostAddress ?: config.peerHost
                    WalkieRuntimeState.lastError = ""
                }
            } catch (t: Throwable) {
                WalkieRuntimeState.resolvedPeer = ""
                WalkieRuntimeState.lastError = "Cannot resolve peer: ${t.message ?: config.peerHost}"
                null
            }
        }

        if (restartTransport || transport == null || previousPort != config.port) {
            restartTransport()
        }
        sendHello()
        updateNotification()
    }

    private fun restartTransport() {
        transport?.close()
        transport = try {
            UdpTransport(config.port, ::onDatagram).also { it.start() }
        } catch (t: Throwable) {
            WalkieRuntimeState.lastError = "UDP ${config.port}: ${t.message ?: "bind failed"}"
            null
        }
    }

    private fun onDatagram(data: ByteArray, source: InetAddress, sourcePort: Int) {
        val expected = peerAddress ?: return
        if (source != expected) return
        val packet = WalkiePacket.decode(data) ?: return
        lastPeerSeenMs = System.currentTimeMillis()
        WalkieRuntimeState.peerOnline = true

        when (packet.type) {
            PacketType.HELLO -> {
                WalkieRuntimeState.remoteName = packet.payload.toString(Charsets.UTF_8).take(40)
            }
            PacketType.PING -> {
                sendPacket(
                    WalkiePacket(PacketType.PONG, packet.sequence, packet.timestampMs),
                    source,
                    sourcePort,
                )
            }
            PacketType.PONG -> {
                val rtt = System.currentTimeMillis() - packet.timestampMs
                if (rtt in 0..60_000) WalkieRuntimeState.rttMs = rtt
            }
            PacketType.PTT_START -> {
                WalkieRuntimeState.remoteTalking = true
                WalkieRuntimeState.remoteName = packet.payload.toString(Charsets.UTF_8).take(40)
                lastRemoteAudioSequence = null
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 70)
            }
            PacketType.AUDIO -> {
                if (WalkieRuntimeState.transmitting) return
                trackPacketLoss(packet.sequence)
                try {
                    playback.write(codec.decode(packet.payload))
                    WalkieRuntimeState.receivedAudioPackets++
                } catch (t: Throwable) {
                    WalkieRuntimeState.lastError = "Decode: ${t.message ?: "audio error"}"
                }
            }
            PacketType.PTT_END -> {
                WalkieRuntimeState.remoteTalking = false
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 70)
            }
        }
    }

    private fun trackPacketLoss(sequence: Int) {
        val previous = lastRemoteAudioSequence
        if (previous != null && sequence > previous + 1) {
            WalkieRuntimeState.lostAudioPackets += (sequence - previous - 1).toLong()
        }
        lastRemoteAudioSequence = sequence
    }

    private fun startTransmitting() {
        if (WalkieRuntimeState.transmitting) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            WalkieRuntimeState.lastError = "Microphone permission is required"
            return
        }
        val address = peerAddress ?: run {
            WalkieRuntimeState.lastError = "Set a Meshnet peer address first"
            return
        }

        WalkieRuntimeState.transmitting = true
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 70)
        sendPacket(
            WalkiePacket(
                PacketType.PTT_START,
                controlSequence.incrementAndGet(),
                payload = config.displayName.toByteArray(Charsets.UTF_8),
            ),
            address,
            config.port,
        )

        try {
            capture.start { pcm ->
                if (WalkieRuntimeState.transmitting) {
                    try {
                        val encoded = codec.encode(pcm)
                        sendPacket(
                            WalkiePacket(PacketType.AUDIO, audioSequence.incrementAndGet(), payload = encoded),
                            address,
                            config.port,
                        )
                    } catch (t: Throwable) {
                        WalkieRuntimeState.lastError = "Encode/send: ${t.message ?: "audio error"}"
                    }
                }
            }
        } catch (t: Throwable) {
            WalkieRuntimeState.lastError = "Microphone: ${t.message ?: "cannot start"}"
            WalkieRuntimeState.transmitting = false
        }
    }

    private fun stopTransmitting() {
        if (!WalkieRuntimeState.transmitting) return
        WalkieRuntimeState.transmitting = false
        capture.stop()
        peerAddress?.let { address ->
            sendPacket(
                WalkiePacket(PacketType.PTT_END, controlSequence.incrementAndGet()),
                address,
                config.port,
            )
        }
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 70)
    }

    private fun startHeartbeat() {
        heartbeat?.shutdownNow()
        heartbeat = Executors.newSingleThreadScheduledExecutor().also { executor ->
            executor.scheduleAtFixedRate({
                val now = System.currentTimeMillis()
                WalkieRuntimeState.peerOnline = lastPeerSeenMs > 0 && now - lastPeerSeenMs < 5_000
                peerAddress?.let { address ->
                    sendPacket(
                        WalkiePacket(PacketType.PING, controlSequence.incrementAndGet(), timestampMs = now),
                        address,
                        config.port,
                    )
                }
            }, 0, 2, TimeUnit.SECONDS)
        }
    }

    private fun sendHello() {
        peerAddress?.let { address ->
            sendPacket(
                WalkiePacket(
                    PacketType.HELLO,
                    controlSequence.incrementAndGet(),
                    payload = config.displayName.toByteArray(Charsets.UTF_8),
                ),
                address,
                config.port,
            )
        }
    }

    private fun sendPacket(packet: WalkiePacket, address: InetAddress, port: Int) {
        try {
            transport?.send(packet.encode(), address, port)
        } catch (t: Throwable) {
            WalkieRuntimeState.lastError = "Network: ${t.message ?: "send failed"}"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "SingleWalkie radio",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Keeps the peer-to-peer walkie receiver available"
                    setSound(null, null)
                },
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("SingleWalkie")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification() {
        val text = if (config.peerHost.isBlank()) {
            "Ready — set a Meshnet peer"
        } else {
            "Listening on UDP ${config.port} • ${config.peerHost}"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopTransmitting()
        heartbeat?.shutdownNow()
        heartbeat = null
        transport?.close()
        transport = null
        playback.close()
        tone?.release()
        tone = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        WalkieRuntimeState.serviceRunning = false
        WalkieRuntimeState.peerOnline = false
        WalkieRuntimeState.remoteTalking = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.edwardmonteiro.singlewalkie.START"
        const val ACTION_UPDATE_CONFIG = "com.edwardmonteiro.singlewalkie.UPDATE_CONFIG"
        const val ACTION_PTT_START = "com.edwardmonteiro.singlewalkie.PTT_START"
        const val ACTION_PTT_STOP = "com.edwardmonteiro.singlewalkie.PTT_STOP"
        const val ACTION_STOP = "com.edwardmonteiro.singlewalkie.STOP"

        private const val CHANNEL_ID = "singlewalkie_radio"
        private const val NOTIFICATION_ID = 1001
    }
}
