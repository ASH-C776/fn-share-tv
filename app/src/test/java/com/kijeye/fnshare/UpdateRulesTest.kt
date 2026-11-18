package com.kijeye.fnshare

import com.kijeye.fnshare.domain.model.UpdateInfo
import com.kijeye.fnshare.domain.model.parseVersionCode
import com.kijeye.fnshare.domain.model.pickLatestUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 新版本挑选的纯函数回归（0.31.0）。
 *
 * 版本号算法必须和 build.gradle.kts 一致，否则「谁更新」会判反——
 * 这里用固定的几组数把边界钉死，不碰网络。
 */
class UpdateRulesTest {

    private fun info(v: String, code: Int, source: String = "gitee") =
        UpdateInfo(v, code, "https://example.com/$v.apk", source)

    @Test
    fun parseVersionCodeMatchesGradleFormula() {
        assertEquals(3000, parseVersionCode("0.30.0"))
        assertEquals(3100, parseVersionCode("0.31.0"))
        assertEquals(3101, parseVersionCode("v0.31.1"))
        assertEquals(10000, parseVersionCode("1.0.0"))
        // 只有两段时 patch 当 0，和 build.gradle 的 getOrElse 一致。
        assertEquals(3100, parseVersionCode("0.31"))
    }

    @Test
    fun parseVersionCodeStripsSuffixAndRejectsJunk() {
        assertEquals(3100, parseVersionCode("v0.31.0-beta"))
        assertEquals(3100, parseVersionCode("0.31.0+build7"))
        assertNull(parseVersionCode("latest"))
        assertNull(parseVersionCode(""))
        assertNull(parseVersionCode("0.31.0.1"))
        assertNull(parseVersionCode("0.x.0"))
    }

    @Test
    fun noUpdateWhenRemoteNotNewer() {
        assertNull(pickLatestUpdate(listOf(info("0.30.0", 3000)), 3000))
        assertNull(pickLatestUpdate(listOf(info("0.29.6", 2906)), 3000))
        assertNull(pickLatestUpdate(emptyList(), 3000))
    }

    @Test
    fun picksHighestVersionAcrossBothSources() {
        // Gitee 漏发了新版，GitHub 上有——也要能挑到。
        val picked = pickLatestUpdate(
            listOf(info("0.30.0", 3000, "gitee"), info("0.31.0", 3100, "github")),
            3000,
        )
        assertEquals("0.31.0", picked?.versionName)
        assertEquals("github", picked?.source)
    }

    @Test
    fun giteeWinsWhenBothSourcesSameVersion() {
        val picked = pickLatestUpdate(
            listOf(info("0.31.0", 3100, "github"), info("0.31.0", 3100, "gitee")),
            3000,
        )
        // 同版本优先 Gitee（国内可达、下载快）。
        assertEquals("gitee", picked?.source)
    }
}
