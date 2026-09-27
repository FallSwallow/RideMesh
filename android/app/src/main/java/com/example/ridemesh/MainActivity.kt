package com.example.ridemesh

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.client.android.Intents
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.BarcodeEncoder

class MainActivity : Activity() {
    private companion object { const val QR_SCAN_REQUEST = 42 }
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var groupKey: TextView
    private lateinit var userName: EditText
    private lateinit var status: TextView
    private lateinit var muteButton: Button
    private val keyChangeControls = mutableListOf<View>()
    private var pendingKey: String? = null
    private var pendingName: String? = null
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
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "RideMesh｜離線車隊通話"
            textSize = 24f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "關於"
            contentDescription = "關於 RideMesh"
            setOnClickListener { showAbout() }
        })
        add(header)
        add(TextView(this).apply { text = "停車時輸入同一組群組金鑰，確認藍牙安全帽耳機後開始。" })
        add(TextView(this).apply { text = "群組金鑰" })
        groupKey = TextView(this).apply {
            hint = "尚未建立或掃描群組金鑰"
            textSize = 18f
            setText(savedInstanceState?.getString("key").orEmpty())
        }
        add(groupKey)
        add(Button(this).apply {
            text = "建立新群組金鑰"
            setOnClickListener { groupKey.setText(Wire.randomBytes(16).toHex()) }
            keyChangeControls.add(this)
        })
        val qrActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        qrActions.addView(Button(this).apply {
            text = "顯示 QR Code"
            setOnClickListener { showKeyQrCode() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        qrActions.addView(Button(this).apply {
            text = "掃描 QR Code"
            setOnClickListener { scanKeyQrCode() }
            keyChangeControls.add(this)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        add(qrActions)
        userName = EditText(this).apply {
            hint = "你的顯示名稱（最多 20 字）"
            isSingleLine = true
            val preferences = getPreferences(MODE_PRIVATE)
            val initialName = savedInstanceState?.getString("name")
                ?: preferences.getString("name", null)
                ?: run {
                    val deviceName = try {
                        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
                    } catch (_: SecurityException) { null }
                    Wire.suggestedName(deviceName, Wire.randomFourDigits()).also {
                        preferences.edit().putString("name", it).apply()
                    }
                }
            setText(initialName)
        }
        add(userName)
        add(Button(this).apply {
            text = "開始對講"
            setOnClickListener { begin() }
        })
        muteButton = Button(this).apply {
            text = "麥克風靜音"
            setOnClickListener {
                RideService.current?.let { service ->
                    service.setMuted(!service.isMuted)
                    muted = service.isMuted
                    text = if (muted) "解除靜音" else "麥克風靜音"
                }
            }
        }
        add(muteButton)
        add(Button(this).apply {
            text = "結束通話"
            setOnClickListener { startService(Intent(this@MainActivity, RideService::class.java).setAction(RideService.ACTION_STOP)) }
        })
        status = TextView(this).apply { textSize = 18f }
        add(status)
        setContentView(ScrollView(this).apply { addView(column) })
        updateStatus()
    }

    private fun begin() {
        val key = groupKey.text.toString().trim().uppercase()
        if (Wire.parseKey(key) == null) {
            Toast.makeText(this, "請輸入 32 字元十六進位群組金鑰", Toast.LENGTH_LONG).show()
            return
        }
        val name = Wire.normalizeName(userName.text.toString())
        if (name == null) {
            Toast.makeText(this, "請填寫顯示名稱（最多 20 字）", Toast.LENGTH_LONG).show()
            return
        }
        pendingKey = key
        pendingName = name
        val required = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        if (Build.VERSION.SDK_INT == 31) required += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) required += Manifest.permission.NEARBY_WIFI_DEVICES
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 11) else startRide(key, name)
    }

    private fun showAbout() {
        val info = packageManager.getPackageInfo(packageName, 0)
        AlertDialog.Builder(this)
            .setTitle("關於 RideMesh")
            .setMessage("版本：${info.versionName ?: "未知"}\n封包加密：${Wire.CIPHER_NAME}")
            .setPositiveButton("關閉", null)
            .show()
    }

    private fun showKeyQrCode() {
        val key = Wire.parseKey(groupKey.text.toString())?.toHex()
        if (key == null) {
            Toast.makeText(this, "請先建立或輸入有效的群組金鑰", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val bitmap: Bitmap = BarcodeEncoder().encodeBitmap(key, BarcodeFormat.QR_CODE, 800, 800)
            val size = (280 * resources.displayMetrics.density).toInt()
            val image = ImageView(this).apply {
                setImageBitmap(bitmap)
                layoutParams = LinearLayout.LayoutParams(size, size)
                contentDescription = "群組金鑰 QR Code"
            }
            AlertDialog.Builder(this)
                .setTitle("群組金鑰 QR Code")
                .setMessage("請讓其他騎士掃描。持有此碼的人可加入頻道。")
                .setView(image)
                .setPositiveButton("關閉", null)
                .show()
        } catch (_: Exception) {
            Toast.makeText(this, "無法產生 QR Code", Toast.LENGTH_LONG).show()
        }
    }

    private fun scanKeyQrCode() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(this, "此裝置沒有可用的相機", Toast.LENGTH_LONG).show()
            return
        }
        val scanner = IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("掃描其他騎士顯示的群組金鑰")
            .setOrientationLocked(true)
            .setBeepEnabled(false)
        startActivityForResult(scanner.createScanIntent(), QR_SCAN_REQUEST)
    }

    @Deprecated("Android activity result callback for the embedded QR scanner")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != QR_SCAN_REQUEST) return
        if (resultCode != RESULT_OK || data == null) {
            if (data?.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, false) == true) {
                Toast.makeText(this, "請允許相機權限以掃描 QR Code", Toast.LENGTH_LONG).show()
            }
            return
        }
        val scanned = IntentIntegrator.parseActivityResult(resultCode, data).contents
        val key = scanned?.let(Wire::parseKey)
        if (key == null) {
            Toast.makeText(this, "QR Code 不是有效的群組金鑰", Toast.LENGTH_LONG).show()
        } else {
            groupKey.setText(key.toHex())
            Toast.makeText(this, "已填入群組金鑰", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 11) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            val key = pendingKey
            val name = pendingName
            if (key != null && name != null) startRide(key, name)
        } else Toast.makeText(this, "通話需要麥克風與鄰近裝置權限", Toast.LENGTH_LONG).show()
        pendingKey = null
        pendingName = null
    }

    private fun startRide(key: String, name: String) {
        getPreferences(MODE_PRIVATE).edit().putString("name", name).apply()
        val intent = Intent(this, RideService::class.java).setAction(RideService.ACTION_START)
            .putExtra(RideService.EXTRA_KEY, key).putExtra(RideService.EXTRA_NAME, name)
        startForegroundService(intent)
    }

    private fun updateStatus() {
        val service = RideService.current
        muted = service?.isMuted ?: false
        muteButton.isEnabled = service != null
        muteButton.text = if (muted) "解除靜音" else "麥克風靜音"
        keyChangeControls.forEach { it.isEnabled = service == null }
        userName.isEnabled = service == null
        status.text = if (service == null) "目前未通話" else {
            val members = service.members
            val names = members.joinToString("\n") {
                val suffix = it.id.takeLast(4)
                if (it.isSelf) "• ${it.name}（我）" else "• ${it.name}（$suffix）"
            }
            "${service.stateText}\n群組：${service.roomId}\n直接連線鄰居：${service.directPeers}" +
                "\n頻道成員（${members.size}）：\n$names"
        }
        ui.postDelayed({ updateStatus() }, 800)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("key", groupKey.text.toString())
        outState.putString("name", userName.text.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
