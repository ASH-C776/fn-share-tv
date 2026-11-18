package com.kijeye.fnshare.data.remote

import android.content.Context
import com.kijeye.fnshare.domain.model.UpdateInfo
import com.kijeye.fnshare.domain.model.parseVersionCode
import com.kijeye.fnshare.domain.model.pickLatestUpdate
import com.kijeye.fnshare.domain.port.UpdateSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 新版本检测与下载。
 *
 * ⚠️ 用**独立的干净 OkHttpClient**，绝不复用 `AppContainer.http.client`：
 * 那个客户端挂着 NAS 的 AccessToken 拦截器与自签证书 pin，拿去访问 Gitee/GitHub
 * 会带上一堆无关请求头，还可能被证书校验挡掉。更新检测跟 NAS 没有任何关系。
 *
 * 两端 API 都不需要 token（仓库公开）：
 * - GitHub: https://api.github.com/repos/ASH-C776/fn-share-tv/releases
 * - Gitee:  https://gitee.com/api/v5/repos/kijeye/fn-share-tv/releases
 *
 * 失败一律吞掉：更新检测是"锦上添花"，任何异常都不该冒到 UI 变成报错弹窗。
 */
class DefaultUpdateSource(
    private val context: Context,
    private val giteeApi: String,
    private val githubApi: String,
) : UpdateSource {

    /**
     * 超时收紧到 8 秒：这是开机后台任务，网络不通时不能拖着。
     * 不设 followRedirects 为 false——Gitee 的附件直链要 302 到 CDN，必须跟随。
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val _available = MutableStateFlow<UpdateInfo?>(null)
    override val available: StateFlow<UpdateInfo?> = _available

    private val _progress = MutableStateFlow<Int?>(null)
    override val progress: StateFlow<Int?> = _progress

    private val _failed = MutableStateFlow(false)
    override val failed: StateFlow<Boolean> = _failed

    /** 下载落地目录：应用私有 filesDir/update，系统不会自动清，但卸载会清。 */
    private val updateDir: File get() = File(context.filesDir, "update")

    override suspend fun check(currentCode: Int) = withContext(Dispatchers.IO) {
        val candidates = coroutineScope {
            val gitee = async { runCatching { fetchReleases(giteeApi, "gitee") }.getOrNull().orEmpty() }
            val github = async { runCatching { fetchReleases(githubApi, "github") }.getOrNull().orEmpty() }
            gitee.await() + github.await()
        }
        val latest = pickLatestUpdate(candidates, currentCode)
        _available.value = latest
        if (latest == null) _failed.value = false
    }

    /**
     * 拉一个端的 Release 列表，转成候选。JSON 结构两端一致：
     * 顶层数组，每项有 tag_name / prerelease / draft / assets[]，
     * asset 有 name / browser_download_url / size。
     */
    private fun fetchReleases(url: String, source: String): List<UpdateInfo> {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }
        if (body.isBlank()) return emptyList()
        val array = JSONArray(body)
        val result = mutableListOf<UpdateInfo>()
        for (i in 0 until array.length()) {
            val release = array.optJSONObject(i) ?: continue
            // 预发布/草稿不算正式版，避免给电视推测试包。
            if (release.optBoolean("prerelease") || release.optBoolean("draft")) continue
            val tag = release.optString("tag_name")
            val code = parseVersionCode(tag) ?: continue
            val assets = release.optJSONArray("assets") ?: continue
            var apkUrl: String? = null
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                val name = asset.optString("name")
                // 只认 APK 附件；Gitee 自动生成的 source.zip/tar.gz 要排除。
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url").ifBlank { null }
                    if (apkUrl != null) break
                }
            }
            if (apkUrl == null) continue
            result += UpdateInfo(
                versionName = tag.removePrefix("v").removePrefix("V"),
                versionCode = code,
                downloadUrl = apkUrl,
                source = source,
            )
        }
        return result
    }

    override suspend fun download(info: UpdateInfo): String? = withContext(Dispatchers.IO) {
        _failed.value = false
        _progress.value = 0
        val file = File(updateDir, "fn-share-tv-${info.versionName}.apk")
        try {
            if (!updateDir.exists() && !updateDir.mkdirs()) {
                throw IllegalStateException("cannot create update dir")
            }
            val request = okhttp3.Request.Builder().url(info.downloadUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("empty body")
                val total = body.contentLength()
                var downloaded = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val percent = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    _progress.value = percent
                                    lastPercent = percent
                                }
                            }
                        }
                    }
                }
                // 大小校验：有 Content-Length 时必须对得上，防止 CDN 返回了半截或错误页。
                if (total > 0 && downloaded != total) {
                    throw IllegalStateException("size mismatch: $downloaded / $total")
                }
            }
            if (file.length() < 1024) throw IllegalStateException("file too small")
            _progress.value = 100
            file.absolutePath
        } catch (e: Exception) {
            _failed.value = true
            _progress.value = null
            runCatching { file.delete() }
            null
        }
    }

    override fun clearFailure() {
        _failed.value = false
    }
}
