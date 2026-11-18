package com.kijeye.fnshare.domain.model

/**
 * 该代码是从哪个原文件提取的：
 * 【新增模型，无函数体被搬动】。飞牛音乐登录的**结果分类**放在 domain，
 * 是为了让 ViewModel 不必 import `data.music.fn` 里那串异常子类，UI 也不必认识 HTTP。
 *
 * 对应的生命周期清理应该在原文件的哪个位置调用：
 * 【无需清理】。一次性结果值，不持有任何对象。
 *
 * 线程约定：由 `FnMusicAuthPort.verify()` 在 IO 上产出，ViewModel 在主线程写进 AppState。
 * 文案由 UI 按分支取 stringResource，domain / data 都不许塞中文提示。
 */
sealed interface FnMusicSignInOutcome {

    /** 两次请求（登录 + 歌单列表）都成功，凭据已落盘。 */
    data class Success(val userName: String) : FnMusicSignInOutcome

    /** 服务端回了 HTML 闸门页：需要访问码，或访问码不对。 */
    data object NeedAccessCode : FnMusicSignInOutcome

    /** 登录接口的 120001 / 120002：账号或密码错（音乐子账户，不是相册账号）。 */
    data object BadCredential : FnMusicSignInOutcome

    /** 连不上、超时、地址写错。 */
    data object Network : FnMusicSignInOutcome

    /** 其它（token 失效、JSON 结构异常等）；detail 只用于显示，不写日志、不落盘。 */
    data class Other(val detail: String) : FnMusicSignInOutcome

    val failed: Boolean get() = this !is Success
}
