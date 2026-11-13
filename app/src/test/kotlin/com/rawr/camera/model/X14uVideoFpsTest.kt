package com.rawr.camera.model

import com.rawr.camera.architecture.CaptureReducer
import com.rawr.camera.architecture.CycleVideoFps
import com.rawr.camera.architecture.CycleVideoResolution
import com.rawr.camera.architecture.SetVideoCrop
import com.rawr.camera.fixtures.CaptureFixtures
import com.rawr.camera.video.VideoCrop
import com.rawr.camera.video.VideoCropKind
import com.rawr.camera.video.VideoFrameSize
import com.rawr.camera.video.VideoResolutionMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** X14U: 60 fps is offered for small, light crops only (the former 1080p among them). */
class X14uVideoFpsTest {
    @Test
    fun sixtyOnlyForSmallCrops() {
        val hd = CaptureUiState(capabilities = CaptureFixtures.baseline().copy(videoSixtyFps = true),
            videoCrop = VideoCrop.Default, videoFps = 30)
        assertEquals(60, CaptureReducer.reduce(hd, CycleVideoFps).videoFps)
        val uhd = hd.copy(videoCrop = VideoCrop.fromLegacy(VideoResolutionMode.UHD4K))
        assertEquals(24, CaptureReducer.reduce(uhd, CycleVideoFps).videoFps)
        // A lens whose RAW stream can't run at 60 (ultra-wide, teles, front, ISZ) skips it.
        val slow = hd.copy(capabilities = hd.capabilities.copy(videoSixtyFps = false))
        assertEquals(24, CaptureReducer.reduce(slow, CycleVideoFps).videoFps)
        val sixty = hd.copy(videoFps = 60)
        val next = CaptureReducer.reduce(sixty, CycleVideoResolution)
        assertEquals(VideoCrop.fromLegacy(VideoResolutionMode.UHD4K), next.videoCrop)
        assertEquals(30, next.videoFps)
        val openGate = CaptureReducer.reduce(sixty, SetVideoCrop(VideoCrop(VideoCropKind.Aspect, aspect = 0, downscale = 0)))
        assertEquals(30, openGate.videoFps)
    }

    @Test
    fun cropSizes() {
        // The former 1080p and 4K keep their exact window and size.
        assertEquals(VideoFrameSize(3840, 2160) to VideoFrameSize(1920, 1080), VideoCrop.Default.sizes(4096, 3072))
        assertEquals(VideoFrameSize(3840, 2160) to VideoFrameSize(3840, 2160),
            VideoCrop.fromLegacy(VideoResolutionMode.UHD4K).sizes(4096, 3072))
        assertEquals(VideoFrameSize(4096, 3072) to VideoFrameSize(4096, 3072),
            VideoCrop.fromLegacy(VideoResolutionMode.OPEN_GATE).sizes(4096, 3072))
        // 16:9 from the full width: 2x lands exactly on the Bayer reduction.
        assertEquals(VideoFrameSize(4096, 2304) to VideoFrameSize(2048, 1152),
            VideoCrop(VideoCropKind.Aspect, aspect = 1, downscale = 3).sizes(4096, 3072))
        assertEquals(VideoFrameSize(4080, 2294) to VideoFrameSize(2720, 1530),
            VideoCrop(VideoCropKind.Aspect, aspect = 1, downscale = 2).sizes(4096, 3072))
        // Saved ids round-trip; the former resolution ids still read.
        val crop = VideoCrop(VideoCropKind.Aspect, aspect = 4, pixel = 2, downscale = 1)
        assertEquals(crop, VideoCrop.fromId(crop.persistedId()))
        assertEquals(VideoCrop.Default, VideoCrop.fromId("1080p"))
        assertEquals(VideoCrop.fromLegacy(VideoResolutionMode.UHD4K), VideoCrop.fromId("4k"))
    }
}
