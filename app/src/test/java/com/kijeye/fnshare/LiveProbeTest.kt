package com.kijeye.fnshare

import com.kijeye.fnshare.data.fn.FnAddress
import com.kijeye.fnshare.data.fn.FnApi
import com.kijeye.fnshare.data.fn.FnCookies
import com.kijeye.fnshare.data.fn.FnLogin
import com.kijeye.fnshare.data.fn.FnSign
import com.kijeye.fnshare.data.fn.FnTrust
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 对着真实 NAS 跑一遍「登录 → 列共享相册」。没有环境变量就跳过，所以 CI / 日常构建不受影响。
 *
 * ```
 * FN_HOST=192.168.1.100 FN_USER=your_user FN_PASS=… FN_CODE=… \
 *   gradle :app:testDebugUnitTest --tests '*LiveProbeTest*' -i
 * ```
 *
 * 先验证接口，再做界面——界面写完才发现取不到数据，是最贵的一种返工。
 */
class LiveProbeTest {

    @Test
    fun loginAndListSharedAlbums() {
        val host = System.getenv("FN_HOST")
        assumeTrue("没有设置 FN_HOST，跳过", !host.isNullOrEmpty())

        val address = FnAddress.parse(host!!) ?: error("地址解析失败: $host")
        val pins = HashMap<String, String>()
        val client = FnTrust(
            loadPin = { pins[it] },
            savePin = { name, value -> if (value == null) pins.remove(name) else pins[name] = value },
        ).newClient(FnCookies())

        val session = FnLogin(client).login(
            address = address,
            userName = System.getenv("FN_USER").orEmpty(),
            password = System.getenv("FN_PASS").orEmpty(),
            accessCode = System.getenv("FN_CODE").orEmpty(),
            deviceId = FnLogin.newDeviceId(),
        )
        println("登录成功：${session.address.baseUrl} token=${session.token.take(8)}…")

        val albums = FnApi(client).sharedAlbums(session)
        println("共享相册 ${albums.size} 个：")
        albums.forEach { println("  [${it.id}] ${it.name} · 来自 ${it.ownerName} · ${it.photoCount} 张") }

        if (albums.isNotEmpty()) {
            val page = FnApi(client).photos(session, albums.first().id, 0, 20)
            println("第一个相册取到 ${page.items.size} 张，还有更多=${page.hasMore}")
            page.items.take(3).forEach { println("  id=${it.id} thumb=${it.thumbUrl?.take(60)}") }

            // 字段名靠猜没用，把第一条原始 JSON 落盘，照它写解析。
            val dump = System.getenv("FN_DUMP")
            if (!dump.isNullOrEmpty()) {
                val params = mapOf(
                    "album_id" to albums.first().id.toString(),
                    "sort_by" to "date_time",
                    "sort_direction" to "desc",
                    "offset" to "0",
                    "limit" to "2",
                )
                val url = session.address.baseUrl.toHttpUrlOrNull()!!.newBuilder()
                    .encodedPath("/p/api/v1/album/photos")
                    .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
                    .build()
                val body = client.newCall(
                    Request.Builder().url(url)
                        .header("AccessToken", session.token)
                        .header("authx", FnSign.authx("/p/api/v1/album/photos", params))
                        .get().build(),
                ).execute().use { it.body?.string().orEmpty() }
                File(dump).writeText(body, Charsets.UTF_8)
                println("原始返回已写入 $dump")

                File(dump.replace(".json", "-albums.json")).writeText(
                    rawGet(client, session, "/p/api/v1/album_grant/list_to_me", mapOf(
                        "offset" to "0",
                        "limit" to "3",
                        "sort_by" to "share_mod_time",
                        "sort_direction" to "desc",
                    )),
                    Charsets.UTF_8,
                )
            }
        }
    }

    private fun rawGet(
        client: okhttp3.OkHttpClient,
        session: com.kijeye.fnshare.data.fn.FnSession,
        path: String,
        params: Map<String, String>,
    ): String {
        val url = session.address.baseUrl.toHttpUrlOrNull()!!.newBuilder()
            .encodedPath(path)
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        return client.newCall(
            Request.Builder().url(url)
                .header("AccessToken", session.token)
                .header("authx", FnSign.authx(path, params))
                .get().build(),
        ).execute().use { it.body?.string().orEmpty() }
    }
}
