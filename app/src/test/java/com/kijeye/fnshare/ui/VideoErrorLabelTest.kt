package com.kijeye.fnshare.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 0.24.13：创维电视取不到 logcat，播放失败的错误码必须能直接显示在屏上供人回报。
 *
 * `TIMEOUT` 是 ViewerScreen 内部约定的哨兵值，翻成中文；
 * 其余是 Media3 `PlaybackException.errorCodeName`，原样保留便于查解码 / 取流原因。
 */
class VideoErrorLabelTest {

    @Test
    fun `超时哨兵翻成中文`() {
        assertEquals("加载超时", videoErrorCodeLabel("TIMEOUT"))
    }

    @Test
    fun `解码失败码原样保留`() {
        assertEquals(
            "ERROR_CODE_DECODING_FAILED",
            videoErrorCodeLabel("ERROR_CODE_DECODING_FAILED"),
        )
    }

    @Test
    fun `取流失败码原样保留`() {
        assertEquals(
            "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            videoErrorCodeLabel("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"),
        )
    }

    @Test
    fun `空码不加工`() {
        assertEquals("", videoErrorCodeLabel(""))
    }
}
