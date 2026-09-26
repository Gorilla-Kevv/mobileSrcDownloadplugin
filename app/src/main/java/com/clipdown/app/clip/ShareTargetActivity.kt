package com.clipdown.app.clip

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.clipdown.app.ui.MainActivity
import kotlinx.coroutines.launch

/**
 * 系统分享入口。
 *
 * 用户在 Instagram / 小红书 里点"分享 → 剪存"，链接直达本应用。
 * 这是**成功率最高**的通道：不受 Android 10+ 剪贴板限制，也不需要悬浮窗权限。
 */
class ShareTargetActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }

        val link = LinkCenter.submit(text, LinkSource.SHARE, force = true)
        lifecycleScope.launch {
            startActivity(
                MainActivity.intent(this@ShareTargetActivity, openParse = link != null)
            )
            finish()
        }
    }
}
