package com.example.ridemesh

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var groupKey: EditText
    private lateinit var status: TextView
    private lateinit var muteButton: Button
    private var pendingKey: String? = null
    private var muted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        fun add(view: android.view.View) {
            column.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        add(TextView(this).apply { text = "RideMesh｜離線車隊通話"; textSize = 24f })
        add(TextView(this).apply { text = "停車時輸入同一組群組金鑰，確認藍牙安全帽耳機後開始。" })
        groupKey = EditText(this).apply {
            hint = "32 字元群組金鑰"
            isSingleLine = true
            setText(savedInstanceState?.getString("key").orEmpty())
        }
        add(groupKey)
        add(Button(this).apply {
            text = "建立新群組金鑰"
            setOnClickListener { groupKey.setText(Wire.randomBytes(16).toHex()) }
        })
        add(Button(this).apply {
            text = "開始對講"
            setOnClickListener { begin() }
        })
        muteButton = Button(this).apply {
            text = "麥克風靜音"
            setOnClickListener {
                muted = !muted
                RideService.current?.setMuted(muted)
                text = if (muted) "解除靜音" else "麥克風靜音"
            }
        }
        add(muteButton)
        add(Button(this).apply {
            text = "結束通話"
            setOnClickListener { startService(Intent(this@MainActivity, RideService::class.java).setAction(RideService.ACTION_STOP)) }
        })
        status = TextView(this).apply { textSize = 18f }
        add(status)
        setContentView(column)
        updateStatus()
    }

    private fun begin() {
        val key = groupKey.text.toString().trim().uppercase()
        if (Wire.parseKey(key) == null) {
            Toast.makeText(this, "請輸入 32 字元十六進位群組金鑰", Toast.LENGTH_LONG).show()
            return
        }
        pendingKey = key
        val required = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        if (Build.VERSION.SDK_INT == 31) required += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) required += Manifest.permission.NEARBY_WIFI_DEVICES
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 11) else startRide(key)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 11) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            pendingKey?.let(::startRide)
        } else Toast.makeText(this, "通話需要麥克風與鄰近裝置權限", Toast.LENGTH_LONG).show()
        pendingKey = null
    }

    private fun startRide(key: String) {
        val intent = Intent(this, RideService::class.java).setAction(RideService.ACTION_START)
            .putExtra(RideService.EXTRA_KEY, key)
        startForegroundService(intent)
    }

    private fun updateStatus() {
        val service = RideService.current
        status.text = if (service == null) "目前未通話" else
            "${service.stateText}\n群組：${service.roomId}\n直接連線鄰居：${service.directPeers}"
        ui.postDelayed({ updateStatus() }, 800)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("key", groupKey.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
