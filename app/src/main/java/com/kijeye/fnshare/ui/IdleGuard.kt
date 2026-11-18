package com.kijeye.fnshare.ui

/**
 * 闲置保护：没人动遥控器超过设定分钟数，就回调一次让界面退回相册墙。
 *
 * 单独成一个小类，因为它和「照片怎么加载」毫无关系，却要在 ViewModel 里占一个字段
 * 加两个方法。挪出来之后 ViewModel 只剩一行调用，[tick] 的判定也能单独验证。
 */
internal class IdleGuard(private val onIdle: () -> Unit) {

    /** 最后一次有按键操作的时间。 */
    private var lastInteractionMs = System.currentTimeMillis()

    /** 任意按键都来这一下，把计时归零。放在 MainActivity 的预览按键监听里统一调用。 */
    fun noteInteraction() {
        lastInteractionMs = System.currentTimeMillis()
    }

    /**
     * 由常驻的定时器周期性点一次。
     *
     * @param idleMinutes 闲置多少分钟算「没人管了」。0 表示关掉这个功能。
     * @param busy 当前是不是「有人在看」（正在放幻灯片或视频）。是的话只重置计时、不动——
     *   这样幻灯片结束（手动暂停）之后才会重新开始算闲置。
     */
    fun tick(idleMinutes: Int, busy: Boolean) {
        if (idleMinutes <= 0) return
        val now = System.currentTimeMillis()
        if (busy) {
            lastInteractionMs = now
            return
        }
        if ((now - lastInteractionMs) / 60_000 >= idleMinutes) {
            lastInteractionMs = now
            onIdle()
        }
    }
}
