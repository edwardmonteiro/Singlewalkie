# SingleWalkie

A minimal Android push-to-talk experiment that sends live Opus voice directly between two phones over NordVPN Meshnet.

## v0.1 architecture

```text
Android A  <--- NordVPN Meshnet --->  Android B
    |                                  |
    +--------- UDP + Opus 16 kHz ------+
```

There is no application backend, Firebase project, account database, or cloud media relay.

## What v0.1 includes

- Native Android / Kotlin app.
- Half-duplex hold-to-talk interaction.
- 16 kHz mono Opus voice at 24 kbit/s.
- 20 ms audio frames over UDP.
- Peer liveness using PING/PONG.
- RTT and packet-loss counters.
- Foreground service for receiver availability.
- GitHub Actions build that uploads a debug APK.

## Phone setup

1. Install NordVPN on both Android phones.
2. Enable Meshnet on both phones.
3. Ensure each phone can see the other Meshnet device.
4. Note the other phone's Meshnet IP or resolvable Meshnet hostname.
5. Install SingleWalkie on both phones.
6. Grant microphone and notification permissions.
7. On phone A, enter phone B's Meshnet address.
8. On phone B, enter phone A's Meshnet address.
9. Keep the UDP port identical on both devices. Default: `45454`.
10. Tap **SAVE & CONNECT**.
11. Wait for **PEER ONLINE**.
12. Hold **HOLD TO TALK** and speak.

For a meaningful network test, put one phone on Wi-Fi and the other on 4G/5G.

## Protocol

Packets begin with `SWK1` and include a type, sequence number, timestamp, payload size, and payload.

Current packet types:

- `HELLO`
- `PING`
- `PONG`
- `PTT_START`
- `AUDIO`
- `PTT_END`

## Audio

- Source: Android `VOICE_COMMUNICATION`.
- PCM: signed 16-bit, mono, 16 kHz.
- Frame: 320 samples / 20 ms.
- Codec: Opus via Concentus.
- Target bitrate: 24 kbit/s.

## Build

The repository CI uses Android Gradle Plugin 9.4.0, Gradle 9.6.0, JDK 17, and Android SDK 36.

Run locally with an installed Gradle 9.6.x:

```bash
gradle :app:assembleDebug
```

The APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Current limitations

v0.1 intentionally keeps the design small:

- One configured peer.
- One shared UDP port.
- No group channels yet.
- No end-to-end application encryption beyond the private Meshnet transport.
- No jitter/reordering layer beyond Android's playback buffer.
- No Bluetooth PTT button yet.
- No audio history or AI transcription.

## License

Apache License 2.0.
