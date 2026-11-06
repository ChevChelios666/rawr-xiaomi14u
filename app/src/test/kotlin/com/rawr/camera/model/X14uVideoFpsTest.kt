package com.rawr.camera.model

import com.rawr.camera.architecture.CaptureReducer
import com.rawr.camera.architecture.CycleVideoFps
import com.rawr.camera.architecture.CycleVideoResolution
import com.rawr.camera.fixtures.CaptureFixtures
import com.rawr.camera.video.VideoResolutionMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** X14U: 60 fps is offered at 1080p only. */
class X14uVideoFpsTest {
    @Test
    fun sixtyOnlyAt1080p() {
        val hd = CaptureUiState(capabilities = CaptureFixtures.baseline(), videoResolution = VideoResolutionMode.HD1080, videoFps = 30)
        assertEquals(60, CaptureReducer.reduce(hd, CycleVideoFps).videoFps)
        val uhd = hd.copy(videoResolution = VideoResolutionMode.UHD4K)
        assertEquals(24, CaptureReducer.reduce(uhd, CycleVideoFps).videoFps)
        val sixty = hd.copy(videoFps = 60)
        val next = CaptureReducer.reduce(sixty, CycleVideoResolution)
        assertEquals(VideoResolutionMode.UHD4K, next.videoResolution)
        assertEquals(30, next.videoFps)
    }
}
