package com.clipdown.app.clip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.clipdown.app.floatwindow.FloatingWindowService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 开机恢复。
 *
 * 仅在用户明确开启过悬浮窗时才拉起服务，避免默认自启动造成打扰。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { FloatingWindowService.tryStartIfEnabled(context) }
            pending.finish()
        }
    }
}
