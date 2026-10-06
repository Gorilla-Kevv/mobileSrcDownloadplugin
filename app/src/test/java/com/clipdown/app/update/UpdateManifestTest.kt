package com.clipdown.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新清单契约测试。
 *
 * 发布脚本 scripts/publish-release.sh 生成的 update.json 必须能被这里解析，
 * 且 **versionCode 单调递增** 是唯一比较依据（versionName 只用于展示）。
 */
class UpdateManifestTest {

    @Test
    fun `解析完整清单`() {
        val body = """
            {
              "versionCode": 7,
              "versionName": "1.0.6",
              "notes": "修复小红书图集去重\n新增应用内更新",
              "apkUrl": "https://example.com/clipdown-release.apk",
              "sha256": "abc123",
              "sizeBytes": 1771116,
              "mandatory": true
            }
        """.trimIndent()
        val info = UpdateRepository.parse(body)
        assertEquals(7, info.versionCode)
        assertEquals("1.0.6", info.versionName)
        assertEquals("https://example.com/clipdown-release.apk", info.apkUrl)
        assertEquals("abc123", info.sha256)
        assertEquals(1771116L, info.sizeBytes)
        assertTrue(info.mandatory)
        assertEquals("1.7 MB", info.sizeText())
    }

    /** apkUrl 缺省时应回落到 release/latest/download 固定链接（发布脚本只需上传资产名一致的 APK） */
    @Test
    fun `缺省 apkUrl 回落固定链接`() {
        val info = UpdateRepository.parse("""{"versionCode":2,"versionName":"1.0.1"}""")
        assertTrue("应回落到固定下载链接: ${info.apkUrl}", info.apkUrl.startsWith("https://github.com/"))
        assertTrue("应指向 latest release: ${info.apkUrl}", info.apkUrl.contains("/releases/latest/download/"))
        assertEquals("", info.notes)
        assertEquals(null, info.sha256)
        assertFalse(info.mandatory)
        assertEquals("", info.sizeText())
    }

    /** 未知字段不能导致解析失败（发布端加字段时老客户端要能忽略） */
    @Test
    fun `忽略未知字段`() {
        val info = UpdateRepository.parse(
            """{"versionCode":3,"versionName":"1.0.2","futureField":{"a":1},"extra":"x"}"""
        )
        assertEquals(3, info.versionCode)
    }

    @Test
    fun `版本比较只看 versionCode`() {
        val info = UpdateInfo(5, "1.0.4", "", "https://e/a.apk", null, null, false)
        assertTrue(info.isNewerThan(4))
        assertFalse(info.isNewerThan(5))
        assertFalse(info.isNewerThan(6))
        // versionName 不参与比较（例如 1.0.10 与 1.0.9 的字符串比较会出错）
        val same = info.copy(versionCode = 5, versionName = "9.9.9")
        assertFalse(same.isNewerThan(5))
    }
}
