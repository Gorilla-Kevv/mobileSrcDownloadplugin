package com.clipdown.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import com.clipdown.app.data.CookieStore
import com.clipdown.parser.model.Platform
import com.clipdown.parser.parsers.IgRiskGuard
import java.net.URLDecoder
import com.clipdown.app.clip.ClipboardMonitor
import com.clipdown.app.data.SettingsRepository
import com.clipdown.app.floatwindow.FloatingWindowService
import com.clipdown.app.ui.nav.AppNav
import com.clipdown.app.ui.theme.ClipDownTheme
import com.clipdown.app.update.UpdateCenter
import com.clipdown.app.update.UpdateLaunchDialog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

        handleDeepLink(intent)
        val openParse = intent?.getBooleanExtra(EXTRA_OPEN_PARSE, false) ?: false
        val openProfile = intent?.getBooleanExtra(EXTRA_OPEN_PROFILE, false) ?: false
        // 演示入口：注入主页演示数据并直接打开主页页，用于无网络/无 Cookie 时走查版式
        //   adb shell am start -n com.clipdown.app/.ui.MainActivity --ez demo_profile true
        val demoProfile = intent?.getBooleanExtra(EXTRA_DEMO_PROFILE, false) ?: false
        if (demoProfile) {
            com.clipdown.app.ui.profile.ProfileCenter.injectDemo()
        }
        setContent {
            ClipDownTheme {
                LaunchedEffect(Unit) { ClipboardMonitor.readAndSubmit(com.clipdown.app.clip.LinkSource.FOREGROUND_CLIP) }
                // 启动静默检查更新：有新版时弹一次提示框（用户点"稍后"本次启动不再打扰）
                LaunchedEffect(Unit) { runCatching { UpdateCenter.check() } }
                UpdateLaunchDialog()
                // 悬浮球权限失效智能引导弹窗（清理缓存/数据后自动引导授权）
                FloatPermissionGuidanceDialog()
                AppNav(openParse = openParse, openProfile = openProfile || demoProfile)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ClipboardMonitor.onForeground()
        ClipboardMonitor.readAndSubmit(com.clipdown.app.clip.LinkSource.FOREGROUND_CLIP)

        // 核心自愈逻辑：清理缓存/数据或杀进程后重新打开应用时，若已开启悬浮球开关且已授权，自动拉起悬浮球服务
        lifecycleScope.launch {
            runCatching {
                val enabled = SettingsRepository(this@MainActivity).floatEnabled.first()
                if (enabled && FloatingWindowService.hasPermission(this@MainActivity)) {
                    FloatingWindowService.tryStart(this@MainActivity)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onPause() {
        ClipboardMonitor.onBackground()
        super.onPause()
    }

    /**
     * 处理来自外部浏览器书签脚本 (Bookmarklet) 的 DeepLink Cookie 回传。
     * 协议格式：clipdown://cookie?platform=instagram&data=<cookie>
     */
    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme?.lowercase() != "clipdown") return
        val host = uri.host?.lowercase() ?: return
        if (host != "cookie" && host != "import-cookie") return

        val platformStr = uri.getQueryParameter("platform") ?: "instagram"
        val rawCookie = uri.getQueryParameter("data")
            ?: uri.getQueryParameter("cookie")
            ?: return

        val targetPlatform = Platform.entries.firstOrNull {
            it.id.equals(platformStr, ignoreCase = true) || it.displayName.equals(platformStr, ignoreCase = true)
        } ?: Platform.INSTAGRAM

        val decodedCookie = runCatching { URLDecoder.decode(rawCookie, "UTF-8") }
            .getOrDefault(rawCookie)
            .trim()

        if (CookieStore.isValidSessionCookie(targetPlatform, decodedCookie)) {
            CookieStore.put(this, targetPlatform, decodedCookie)
            IgRiskGuard.reset()
            Toast.makeText(
                this,
                "✓ 已通过手机浏览器成功导入 ${targetPlatform.displayName} 登录态！\n已自动解除风控限制，随时可开始解析。",
                Toast.LENGTH_LONG
            ).show()
        } else {
            val tokenName = when (targetPlatform) {
                Platform.INSTAGRAM -> "sessionid"
                Platform.XIAOHONGSHU -> "web_session"
                Platform.FACEBOOK -> "c_user"
                else -> "核心鉴权令牌"
            }
            Toast.makeText(
                this,
                "导入失败：未能检测到有效登录态（缺少 $tokenName）。\n请确认已在手机浏览器中登录账号，再运行书签脚本！",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        const val EXTRA_OPEN_PARSE = "extra_open_parse"

        /** 直接进入博主主页页（气泡「打开主页」与演示入口共用） */
        const val EXTRA_OPEN_PROFILE = "extra_open_profile"

        /** 演示用：注入主页演示数据并直接进入主页页（版式走查） */
        const val EXTRA_DEMO_PROFILE = "demo_profile"

        fun intent(
            context: Context,
            openParse: Boolean = false,
            openProfile: Boolean = false
        ): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_PARSE, openParse)
                .putExtra(EXTRA_OPEN_PROFILE, openProfile)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

/**
 * 悬浮球权限智能引导弹窗：
 * 当用户清理缓存/数据后导致权限失效或服务终止时，启动时温和弹窗指引开启，点击直接跳系统设置，返回后自动启动悬浮球。
 */
@Composable
private fun FloatPermissionGuidanceDialog() {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }
    val floatEnabled by settings.floatEnabled.collectAsStateWithLifecycle(initialValue = true)
    var dismissed by rememberSaveable { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(FloatingWindowService.hasPermission(context)) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = FloatingWindowService.hasPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (floatEnabled && !hasPermission && !dismissed) {
        AlertDialog(
            shape = RectangleShape,
            onDismissRequest = { dismissed = true },
            title = {
                Text("恢复屏幕悬浮球", style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Text(
                    "检测到「悬浮窗权限」尚未开启（清理应用缓存/数据或系统重置后可能需要重新授权）。\n\n开启权限后悬浮球将自动恢复常驻桌面，复制链接即可在任意应用上一键识别下载。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    shape = RectangleShape,
                    onClick = {
                        dismissed = true
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                ) {
                    Text("前往开启权限", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(
                    shape = RectangleShape,
                    onClick = { dismissed = true }
                ) {
                    Text("暂不需要")
                }
            }
        )
    }
}
