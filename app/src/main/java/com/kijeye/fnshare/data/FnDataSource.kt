package com.kijeye.fnshare.data

import com.kijeye.fnshare.data.fn.PhotoPage
import com.kijeye.fnshare.data.fn.SharedAlbum
import com.kijeye.fnshare.data.fn.FnSession
import okhttp3.OkHttpClient

/**
 * 登录那一摊：换 token、退出。
 *
 * 拆出来是为了让上层（ViewModel）不认识具体的 [FnLogin]／WebSocket 握手细节——
 * 写测试时换一个假实现就能验证「登录失败要不要弹访问码」这类分支。
 */
interface FnAuth {

    /**
     * 走完整握手登录。失败时抛具体异常（访问码缺失、二次验证、地址错误……），
     * 由上层决定是弹窗还是显示错误，这里不吞。
     */
    fun signIn(rawHost: String, userName: String, password: String, accessCode: String): FnSession

    /** 用本地存下来的账号重新登。失败原因同样如实抛出。 */
    fun resume(): FnSession

    /** 清掉 token 和存档的账号密码（显示设置不属于账号，不动）。 */
    fun signOut()
}

/**
 * 数据请求那一摊。
 *
 * 两个方法都自带「token 过期就静默重登一次再重放」：飞牛的 token 有有效期，
 * 而这个应用可能连续开机好几天，过期处理必须由数据层兜住，不能交给每个调用点。
 */
interface FnPhotoApi {

    /** 别人共享给我的相册。返回接口给的原顺序。 */
    fun albums(): List<SharedAlbum>

    /** 一个相册的照片分页。视频也在里面，由上层决定怎么显示。 */
    fun photos(albumId: Long, offset: Int, limit: Int): PhotoPage
}

/**
 * 一个数据源＝登录 + 取数 + 一个共用的 HTTP 客户端。
 *
 * 图片加载（Coil）和视频取流（ExoPlayer）都要复用这个 [client]：缩略图和视频流
 * 不要签名但要 `AccessToken`，凭证是挂在客户端拦截器上的，换成别的客户端就全漏了。
 */
interface FnDataSource : FnAuth, FnPhotoApi {
    val client: OkHttpClient
}
