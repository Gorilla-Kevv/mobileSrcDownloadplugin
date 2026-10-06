package com.clipdown.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import com.clipdown.app.clip.ClipboardMonitor
import com.clipdown.app.ui.nav.AppNav
import com.clipdown.app.ui.theme.ClipDownTheme
import com.clipdown.app.update.UpdateCenter
import com.clipdown.app.update.UpdateLaunchDialog

class MainActivity : ComponentActivity() {

    private val requestNotification = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果不影响主流程，下载时再提示即可 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ClipboardMonitor.onForeground()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val openParse = intent?.getBooleanExtra(EXTRA_OPEN_PARSE, false) ?: false
        setContent {
            ClipDownTheme {
                LaunchedEffect(Unit) { ClipboardMonitor.readAndSubmit(com.clipdown.app.clip.LinkSource.FOREGROUND_CLIP) }
                // 启动静默检查更新：有新版时弹一次提示框（用户点"稍后"本次启动不再打扰）
                LaunchedEffect(Unit) { runCatching { UpdateCenter.check() } }
                UpdateLaunchDialog()
                AppNav(openParse = openParse)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ClipboardMonitor.onForeground()
        ClipboardMonitor.readAndSubmit(com.clipdown.app.clip.LinkSource.FOREGROUND_CLIP)
    }

    override fun onPause() {
        ClipboardMonitor.onBackground()
        super.onPause()
    }

    companion object {
        const val EXTRA_OPEN_PARSE = "extra_open_parse"

        fun intent(context: Context, openParse: Boolean = false): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_PARSE, openParse)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
