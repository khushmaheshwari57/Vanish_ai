package com.vanish.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var button: Button
    private lateinit var keyInput: EditText
    private var wantStart = false
    private var askedPerms = false
    private var askedNotif = false
    private var askedOverlay = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        button = findViewById(R.id.listenButton)
        keyInput = findViewById(R.id.apiKey)
        button.setOnClickListener { toggle() }
        showState(VanishService.running)
    }

    override fun onResume() {
        super.onResume()
        if (wantStart) proceed() else showState(VanishService.running)
    }

    private fun prefs() = getSharedPreferences("vanish", MODE_PRIVATE)

    private fun hasKey() = !prefs().getString("api_key", "").isNullOrBlank()

    private fun saveKey() {
        val k = keyInput.text.toString().trim()
        if (k.isNotEmpty()) {
            prefs().edit().putString("api_key", k).apply()
            keyInput.setText("")
            Toast.makeText(this, "Key save ho gayi ✅", Toast.LENGTH_SHORT).show()
        }
    }

    private fun granted(p: String) =
        checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun toggle() {
        if (VanishService.running) {
            stopService(Intent(this, VanishService::class.java))
            wantStart = false
            showState(false)
        } else {
            saveKey()
            wantStart = true
            askedPerms = false
            proceed()
        }
    }

    private fun proceed() {
        if (!wantStart) return

        val need = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE
        ).filter { !granted(it) }

        if (need.isNotEmpty() && !askedPerms) {
            askedPerms = true
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            wantStart = false
            status.text = "Mic permission zaroori hai. Settings > Apps > Vanish > Permissions mein Allow karo."
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotif &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            askedNotif = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            return
        }
        if (!Settings.canDrawOverlays(this) && !askedOverlay) {
            askedOverlay = true
            Toast.makeText(this, "Vanish ke liye 'Display over other apps' ON karo", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }

        wantStart = false
        val svc = Intent(this, VanishService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        showState(true)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        proceed()
    }

    private fun showState(on: Boolean) {
        keyInput.hint = if (hasKey()) "Key saved ✅ (badalni ho to nayi paste karo)" else "API key yahan paste karo"
        if (on) {
            status.text = "Vanish chalu hai ✅\n\nBolo: \"Hey Vanish\" aur phir kuch bhi pucho,\nya \"mummy ko call karo\", \"free fire max\""
            button.text = "BAND KARO"
        } else {
            status.text = "Vanish band hai"
            button.text = "VANISH CHALU KARO"
        }
    }
}
