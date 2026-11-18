package com.kijeye.fnshare

import com.kijeye.fnshare.domain.model.MusicEntry
import com.kijeye.fnshare.domain.model.MusicGroup
import com.kijeye.fnshare.domain.model.MusicSourceKind
import com.kijeye.fnshare.domain.model.hasFnSelection
import com.kijeye.fnshare.domain.model.isFnSelectionId
import com.kijeye.fnshare.domain.model.selectedFnGroupTitle
import com.kijeye.fnshare.presentation.state.AppState
import com.kijeye.fnshare.presentation.state.selectableIds
import com.kijeye.fnshare.presentation.state.MusicCatalogUi
import com.kijeye.fnshare.presentation.state.MusicMenuLabel
import com.kijeye.fnshare.presentation.state.buildMusicMenuLabel
import com.kijeye.fnshare.presentation.state.visibleMusicEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 双源曲库的**纯状态回归**：VM 的 ensureMusicPlaying / toggleMusicAll 与 UI 的
 * 「已选 N 首」共用 `visibleMusicEntries()`，两边口径一旦分叉就会出现
 * 「显示已全选、按下去却没选中」这类老 bug。全部纯 JVM，不需要设备。
 */
class MusicCatalogUiTest {

    private val local = listOf(
        MusicEntry(id = "file:/sdcard/Music/a.mp3", title = "本地A"),
        MusicEntry(id = "file:/sdcard/Music/b.flac", title = "本地B"),
    )
    private val remote = listOf(
        MusicEntry(id = "fn:track-1", title = "飞牛1"),
    )

    @Test
    fun localSourceUsesOnlyLocalEntries() {
        val state = AppState(musicEntries = local, fnMusicTracks = remote)
        assertSame(local, state.visibleMusicEntries())
    }

    @Test
    fun fnSourceUsesOnlyPlaylistTracks() {
        val state = AppState(
            musicSourceKind = MusicSourceKind.FN,
            musicEntries = local,
            fnMusicTracks = remote,
        )
        assertSame(remote, state.visibleMusicEntries())
    }

    @Test
    fun switchingSourcesKeepsBothListsIntact() {
        val base = AppState(musicEntries = local, fnMusicTracks = remote)
        val toFn = base.copy(musicSourceKind = MusicSourceKind.FN)
        val back = toFn.copy(musicSourceKind = MusicSourceKind.LOCAL)
        assertEquals(remote, toFn.visibleMusicEntries())
        assertEquals(local, back.visibleMusicEntries())
    }

    @Test
    fun fnIdsDoNotLeakIntoLocalSelectionCount() {
        val state = AppState(
            musicEntries = local,
            musicSelected = setOf(local[0].id, remote[0].id),
        )
        // 幽灵勾选不计入显示口径，与 VM 的 toggleMusicAll 同一套判据。
        assertEquals(1, state.musicEntries.count { it.id in state.musicSelected })
    }

    @Test
    fun groupIdNonEmptyMeansTrackStep() {
        val groups = listOf(MusicGroup("fnpl:pl-1", "相册专用"))
        val ui = MusicCatalogUi(
            sourceKind = MusicSourceKind.FN,
            groups = groups,
            currentGroupId = groups[0].id,
            currentGroupTitle = groups[0].title,
            tracks = remote,
        )
        assertEquals(groups[0].id, ui.currentGroupId)
        assertEquals(1, ui.tracks.size)
    }

    @Test
    fun defaultsAreLocalAndNoGroupStep() {
        val ui = MusicCatalogUi()
        assertEquals(MusicSourceKind.LOCAL, ui.sourceKind)
        assertNull(ui.currentGroupId)
        assertEquals(emptyList<MusicGroup>(), ui.groups)
    }

    // ---- 0.20.1 回归：歌单按「勾选」处理，背景乐只由幻灯片驱动 ----

    private val groups = listOf(
        MusicGroup("fnpl:pl-1", "歌单一"),
        MusicGroup("fnpl:pl-2", "相册专用"),
    )

    @Test
    fun fnGroupStepSelectsPlaylistsNotTracks() {
        val state = AppState(
            musicSourceKind = MusicSourceKind.FN,
            fnMusicGroups = groups,
            fnMusicTracks = remote,
            musicSelected = setOf("fnpl:pl-2"),
        )
        assertEquals(setOf("fnpl:pl-1", "fnpl:pl-2"), state.selectableIds())
        assertEquals(setOf("fn:track-1"), state.visibleMusicEntries().map { it.id }.toSet())
    }

    @Test
    fun fnTrackStepSelectsTracksAgain() {
        val state = AppState(
            musicSourceKind = MusicSourceKind.FN,
            fnMusicGroups = groups,
            fnMusicTracks = remote,
            fnMusicCurrentGroupId = "fnpl:pl-2",
        )
        assertEquals(setOf("fn:track-1"), state.selectableIds())
    }

    @Test
    fun localStepStillSelectsLocalTracks() {
        val state = AppState(musicEntries = local, fnMusicGroups = groups)
        assertEquals(setOf(local[0].id, local[1].id), state.selectableIds())
    }

    @Test
    fun coldStartHasPickedPlaylistButNoTracks() {
        // 复现「播放幻灯片没声音」的根因：勾选的歌单 id 落盘了，飞牛曲目列表
        // 不落盘，冷启后为空。旧逻辑拿勾选去 orderedSelection 曲目列表，
        // 得空就 stop，于是勾好歌单再开幻灯片永远静音。
        val state = AppState(
            musicSourceKind = MusicSourceKind.FN,
            fnMusicGroups = groups,
            musicSelected = setOf("fnpl:pl-2"),
            fnMusicTracks = emptyList(),
        )
        assertEquals(emptyList<MusicEntry>(), state.visibleMusicEntries())
        assertEquals(true, hasFnSelection(state.musicSelected))
        assertEquals(0, state.visibleMusicEntries().count { it.id in state.musicSelected })
    }

    @Test
    fun fnSelectionDetectionCoversPlaylistTrackAndLegacyIds() {
        // 歌单 id / 旧版残留的曲目 id 都要展开；只有 file: 时（从本地切来、
        // 还没勾过飞牛歌单）不该为展开歌单发网络请求。
        assertEquals(true, hasFnSelection(setOf("fnpl:pl-1")))
        assertEquals(true, hasFnSelection(setOf("fn:track-1")))
        assertEquals(true, hasFnSelection(setOf("file:/x.mp3", "fnpl:pl-1")))
        assertEquals(false, hasFnSelection(setOf("file:/sdcard/Music/a.mp3")))
        assertEquals(false, hasFnSelection(emptySet()))
    }

    // ---- 幻灯片二级菜单「音乐」行：只报勾选摘要，不带账号 ----

    @Test
    fun menuShowsPickedPlaylistTitleAfterSlash() {
        assertEquals("相册专用", selectedFnGroupTitle(setOf("fnpl:pl-2"), groups))
    }

    @Test
    fun menuFallsBackToSourceWhenNothingPicked() {
        assertNull(selectedFnGroupTitle(emptySet(), groups))
        // 从本地切过来、只勾过 file: 曲目时，不该拿本地文件名当飞牛歌单名。
        assertNull(selectedFnGroupTitle(setOf("file:/sdcard/Music/a.mp3"), groups))
        // 旧版落盘的 fn: 曲目 id 没有对应歌单名，也退回只显示来源。
        assertNull(selectedFnGroupTitle(setOf("fn:track-1"), groups))
    }

    @Test
    fun menuIsStableOnColdStartAndWithMultiplePicks() {
        // 冷启歌单列表还没拉回来：不能编一个名字出来。
        assertNull(selectedFnGroupTitle(setOf("fnpl:pl-2"), emptyList()))
        // 多选时按歌单列表顺序取第一张，Set 迭代顺序不会让菜单文字乱跳。
        assertEquals("歌单一", selectedFnGroupTitle(setOf("fnpl:pl-2", "fnpl:pl-1"), groups))
    }

    // ---- 0.21.1 定稿：本地 N首 / 飞牛音乐/歌单名 / 未选择 ----

    @Test
    fun menuLabelLocalWithPicksIsLocalCount() {
        // 选择集里残留的飞牛勾选不影响本地这一行，也不该把账号带进来。
        assertEquals(
            MusicMenuLabel.Local(5),
            buildMusicMenuLabel(
                sourceKind = MusicSourceKind.LOCAL,
                localSelectedCount = 5,
                fnSelected = true,
                fnGroupTitle = "相册专用",
            ),
        )
    }

    @Test
    fun menuLabelNothingPickedIsNoneForBothSources() {
        // 没勾选一律「未选择」，不再退回显示「本地音乐 / 飞牛音乐」。
        assertEquals(
            MusicMenuLabel.None,
            buildMusicMenuLabel(MusicSourceKind.LOCAL, 0, false, null),
        )
        // 从本地切到飞牛、只勾过 file: 曲目时，飞牛这一行仍是「未选择」。
        assertEquals(
            MusicMenuLabel.None,
            buildMusicMenuLabel(MusicSourceKind.FN, 5, false, null),
        )
    }

    @Test
    fun menuLabelFnWithPickedPlaylistCarriesTitle() {
        val selected = setOf("fnpl:pl-2", "file:/sdcard/Music/a.mp3")
        assertEquals(
            MusicMenuLabel.Fn("相册专用"),
            buildMusicMenuLabel(
                sourceKind = MusicSourceKind.FN,
                localSelectedCount = 1,
                fnSelected = hasFnSelection(selected),
                fnGroupTitle = selectedFnGroupTitle(selected, groups),
            ),
        )
    }

    @Test
    fun menuLabelFnColdStartStillCountsAsPicked() {
        // 冷启歌单名还没拉回来：仍是「有勾选」，只写「飞牛音乐」，不能落成「未选择」。
        assertEquals(
            MusicMenuLabel.Fn(null),
            buildMusicMenuLabel(MusicSourceKind.FN, 0, true, null),
        )
    }

    @Test
    fun fnIdsNeverCountAsLocalTracks() {
        val selected = setOf("file:/sdcard/Music/a.mp3", "fnpl:pl-2", "fn:track-1")
        assertEquals(1, selected.count { !isFnSelectionId(it) })
    }

    @Test
    fun menuLabelNeverCarriesAccountName() {
        // 纯函数入参里根本没有账号，任何来源 / 登录组合都拼不出「已登录：tv」。
        assertEquals(
            listOf(MusicMenuLabel.Local(5), MusicMenuLabel.Fn("相册专用"), MusicMenuLabel.None),
            listOf(
                buildMusicMenuLabel(MusicSourceKind.LOCAL, 5, true, "相册专用"),
                buildMusicMenuLabel(MusicSourceKind.FN, 0, true, "相册专用"),
                buildMusicMenuLabel(MusicSourceKind.FN, 0, false, null),
            ),
        )
    }
}
