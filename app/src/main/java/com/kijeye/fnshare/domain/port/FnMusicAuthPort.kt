package com.kijeye.fnshare.domain.port

import com.kijeye.fnshare.domain.model.FnMusicSignInOutcome

/**
 * 该代码是从哪个原文件提取的：
 * 【新增契约，无函数体被搬动】。设置页登录弹窗与 ViewModel 只认这个接口，
 * 不认识 `FnMusicSession` / `FnMusicApi` / OkHttp，也不认识任何异常子类。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。实现只持有会话与设置引用；token 在内存里由会话自己管。
 * `signOut()` 是用户主动动作，不是生命周期清理；相册 `forget()` 不会调用它。
 *
 * 线程约定：属性同步读（主线程）；[verify] 自己切到 Dispatchers.IO。
 */
interface FnMusicAuthPort {

    /** 已保存音乐子账户（冷启可重登）即算已登录。 */
    val loggedIn: Boolean

    /** 设置页显示用，例如「已登录：tv」。 */
    val accountName: String

    /** 已保存的地址；未登录时是空串。 */
    val baseUrl: String

    /** 已保存的账号名，用来回填登录表单。 */
    val savedUser: String

    /** 由相册 host 推出来的初始地址，只作表单默认值，用户可以整个改掉。 */
    val defaultBaseUrl: String

    /**
     * 真发 `login` 再真发 `playlist/list`，两次都成功才落盘。
     * 失败一律转成 [FnMusicSignInOutcome]，不把异常抛到 UI 层。
     */
    suspend fun verify(
        url: String,
        user: String,
        password: String,
        accessCode: String,
    ): FnMusicSignInOutcome

    /** 清掉音乐凭据（不动相册账号），并把来源切回本地。 */
    fun signOut()
}
