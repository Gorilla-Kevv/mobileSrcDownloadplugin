package com.clipdown.parser.parsers

import com.clipdown.parser.model.Platform
import com.clipdown.parser.model.ParseException
import com.clipdown.parser.spi.ParseContext

/**
 * Instagram 风控保险丝（修复 11 的延伸）。
 *
 * 单篇链路 [InstagramParser] 与主页链路 [InstagramProfileParser] **共用这一份状态**：
 * 主页一次要拉整页列表，比单篇更容易撞风控，若两条链路各记各的失败数，
 * 用户"主页失败 → 再点单篇"就能绕开冷却把账号打得更狠。
 *
 * 规则不变：连续失败 [THRESHOLD] 次进入 [COOLDOWN_MS] 冷却，
 * 冷却期内任何 IG 解析**直接抛出、一个请求都不发**。
 */
object IgRiskGuard {

    private const val THRESHOLD = 2
    private const val COOLDOWN_MS = 10 * 60_000L

    @Volatile
    private var failStreak = 0

    @Volatile
    private var cooldownUntil = 0L

    /** 冷却中剩余分钟数（0 = 未冷却），供 UI 与测试观察 */
    val remainingMinutes: Int
        get() {
            val left = cooldownUntil - System.currentTimeMillis()
            return if (left <= 0) 0 else (left / 60_000).toInt() + 1
        }

    fun <T> guard(ctx: ParseContext, caller: String, block: () -> T): T {
        val now = System.currentTimeMillis()
        val remainMin = remainingMinutes
        if (remainMin > 0) {
            throw ParseException(
                "Instagram 解析冷却中（连续失败保护，账号风控恢复期），约 $remainMin 分钟后自动恢复",
                Platform.INSTAGRAM,
                retryable = false
            )
        }
        val result = runCatching(block)
        if (result.isSuccess) {
            failStreak = 0
            return result.getOrThrow()
        }
        failStreak++
        if (failStreak >= THRESHOLD) {
            cooldownUntil = now + COOLDOWN_MS
            ctx.log(
                caller,
                "连续失败 $failStreak 次，冷却 ${COOLDOWN_MS / 60_000} 分钟（风控保护，期间不再发起请求）"
            )
            failStreak = 0
        }
        throw result.exceptionOrNull() ?: ParseException("解析失败", Platform.INSTAGRAM)
    }

    /** 仅供测试复位 */
    fun resetForTest() {
        failStreak = 0
        cooldownUntil = 0L
    }
}
