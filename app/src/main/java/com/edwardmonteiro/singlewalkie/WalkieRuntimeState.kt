package com.edwardmonteiro.singlewalkie

object WalkieRuntimeState {
    @Volatile var serviceRunning: Boolean = false
    @Volatile var peerOnline: Boolean = false
    @Volatile var transmitting: Boolean = false
    @Volatile var remoteTalking: Boolean = false
    @Volatile var resolvedPeer: String = ""
    @Volatile var remoteName: String = ""
    @Volatile var rttMs: Long = -1
    @Volatile var receivedAudioPackets: Long = 0
    @Volatile var lostAudioPackets: Long = 0
    @Volatile var lastError: String = ""

    fun resetSessionStats() {
        rttMs = -1
        receivedAudioPackets = 0
        lostAudioPackets = 0
        lastError = ""
    }
}
