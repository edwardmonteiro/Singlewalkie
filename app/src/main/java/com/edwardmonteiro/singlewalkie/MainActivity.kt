package com.edwardmonteiro.singlewalkie

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var nameInput: EditText
    private lateinit var peerInput: EditText
    private lateinit var portInput: EditText
    private lateinit var statusText: TextView
    private lateinit var peerText: TextView
    private lateinit var statsText: TextView
    private lateinit var errorText: TextView
    private lateinit var talkButton: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        buildUi()
        loadConfigIntoUi()
        ensurePermissionsAndStart()
        handler.post(refreshTask)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTask)
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(24), dp(18), dp(24), dp(18))
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val top = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars()).top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            view.setPadding(dp(24), top + dp(18), dp(24), bottom + dp(18))
            insets
        }

        root.addView(TextView(this).apply {
            text = "SINGLEWALKIE"
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        })
        root.addView(TextView(this).apply {
            text = "Mesh PTT • direct device-to-device audio"
            setTextColor(MUTED)
            textSize = 14f
            setPadding(0, dp(4), 0, dp(18))
        })

        statusText = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = rounded(AMBER, 999f)
            setTextColor(Color.BLACK)
        }
        root.addView(statusText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        peerText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(18), 0, dp(2))
        }
        root.addView(peerText)

        statsText = TextView(this).apply {
            setTextColor(MUTED)
            textSize = 13f
        }
        root.addView(statsText)

        root.addView(Space(this), LinearLayout.LayoutParams(1, dp(18)))
        nameInput = addInput(root, "MY NAME", "Edward")
        peerInput = addInput(root, "MESHNET PEER", "100.x.x.x or Meshnet hostname")
        portInput = addInput(root, "UDP PORT", WalkieConfig.DEFAULT_PORT.toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        val saveButton = Button(this).apply {
            text = "SAVE & CONNECT"
            setTextColor(Color.BLACK)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            background = rounded(GREEN, 14f)
            setOnClickListener {
                saveAndApply()
                Toast.makeText(this@MainActivity, "Peer configuration applied", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(saveButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply {
            topMargin = dp(8)
        })

        root.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))

        talkButton = TextView(this).apply {
            text = "HOLD\nTO TALK"
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true
            isFocusable = true
            background = rounded(GREEN, 110f)
            setOnTouchListener(::onTalkTouch)
        }
        val talkSize = dp(220)
        root.addView(talkButton, LinearLayout.LayoutParams(talkSize, talkSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        errorText = TextView(this).apply {
            setTextColor(RED)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(errorText)

        root.addView(TextView(this).apply {
            text = "NordVPN Meshnet carries the private network. SingleWalkie carries only UDP + Opus voice."
            setTextColor(MUTED)
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })

        setContentView(root)
    }

    private fun addInput(parent: LinearLayout, label: String, hintText: String): EditText {
        parent.addView(TextView(this).apply {
            text = label
            setTextColor(MUTED)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(0, dp(6), 0, dp(5))
        })
        return EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.rgb(105, 115, 125))
            setTextColor(Color.WHITE)
            textSize = 16f
            isSingleLine = true
            background = rounded(PANEL, 12f, BORDER)
            setPadding(dp(14), 0, dp(14), 0)
            parent.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply {
                bottomMargin = dp(10)
            })
        }
    }

    private fun onTalkTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!hasMicrophonePermission()) {
                    ensurePermissionsAndStart()
                    Toast.makeText(this, "Allow microphone access first", Toast.LENGTH_SHORT).show()
                    return true
                }
                if (peerInput.text.toString().trim().isBlank()) {
                    Toast.makeText(this, "Enter the other phone's Meshnet address", Toast.LENGTH_SHORT).show()
                    return true
                }
                saveAndApply()
                sendServiceAction(WalkieService.ACTION_PTT_START)
                talkButton.text = "TALKING"
                talkButton.background = rounded(RED, 110f)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                sendServiceAction(WalkieService.ACTION_PTT_STOP)
                talkButton.text = "HOLD\nTO TALK"
                talkButton.background = rounded(GREEN, 110f)
                view.performClick()
                return true
            }
        }
        return false
    }

    private fun loadConfigIntoUi() {
        val config = WalkieConfig.load(this)
        nameInput.setText(config.displayName)
        peerInput.setText(config.peerHost)
        portInput.setText(config.port.toString())
    }

    private fun saveAndApply() {
        val port = portInput.text.toString().toIntOrNull()?.coerceIn(1024, 65535) ?: WalkieConfig.DEFAULT_PORT
        val config = WalkieConfig(
            displayName = nameInput.text.toString().trim().ifBlank { "Walkie" },
            peerHost = peerInput.text.toString().trim(),
            port = port,
        )
        WalkieConfig.save(this, config)
        portInput.setText(port.toString())
        sendServiceAction(WalkieService.ACTION_UPDATE_CONFIG)
    }

    private fun refreshStatus() {
        val online = WalkieRuntimeState.peerOnline
        statusText.text = when {
            !WalkieRuntimeState.serviceRunning -> "● RADIO STOPPED"
            online -> "● PEER ONLINE"
            else -> "● WAITING FOR PEER"
        }
        statusText.background = rounded(if (online) GREEN else AMBER, 999f)

        val remote = WalkieRuntimeState.remoteName.ifBlank { peerInput.text.toString().trim().ifBlank { "No peer configured" } }
        peerText.text = when {
            WalkieRuntimeState.remoteTalking -> "$remote  •  TALKING"
            WalkieRuntimeState.transmitting -> "$remote  •  RECEIVING YOU"
            else -> remote
        }

        val received = WalkieRuntimeState.receivedAudioPackets
        val lost = WalkieRuntimeState.lostAudioPackets
        val total = received + lost
        val lossPercent = if (total == 0L) 0.0 else lost * 100.0 / total
        val rtt = if (WalkieRuntimeState.rttMs >= 0) "${WalkieRuntimeState.rttMs} ms RTT" else "RTT —"
        val resolved = WalkieRuntimeState.resolvedPeer.ifBlank { "unresolved" }
        statsText.text = String.format(Locale.US, "%s  •  loss %.1f%%  •  %s", rtt, lossPercent, resolved)
        errorText.text = WalkieRuntimeState.lastError

        if (!WalkieRuntimeState.transmitting && talkButton.text == "TALKING") {
            talkButton.text = "HOLD\nTO TALK"
            talkButton.background = rounded(GREEN, 110f)
        }
    }

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (!hasMicrophonePermission()) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) {
            startWalkieService()
        } else {
            requestPermissions(needed.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && hasMicrophonePermission()) startWalkieService()
    }

    private fun hasMicrophonePermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startWalkieService() {
        val intent = Intent(this, WalkieService::class.java).setAction(WalkieService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, WalkieService::class.java).setAction(action)
        if (!WalkieRuntimeState.serviceRunning && action != WalkieService.ACTION_STOP) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        } else {
            startService(intent)
        }
    }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            strokeColor?.let { setStroke(dp(1), it) }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val REQUEST_PERMISSIONS = 7
        private val BG = Color.rgb(11, 15, 20)
        private val PANEL = Color.rgb(22, 27, 34)
        private val BORDER = Color.rgb(48, 54, 61)
        private val MUTED = Color.rgb(139, 148, 158)
        private val GREEN = Color.rgb(126, 231, 135)
        private val AMBER = Color.rgb(210, 153, 34)
        private val RED = Color.rgb(248, 81, 73)
    }
}
