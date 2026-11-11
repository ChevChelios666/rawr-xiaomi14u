package com.rawr.camera.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.rawr.camera.model.ScopePresentation

/** UI-facing preview slots and measurements. Native integration belongs to CaptureRoute. */
data class CapturePreview(
    val nativeContent: Boolean = false,
    val deviceRotationDegrees: Int = 0,
    val content: @Composable (Modifier) -> Unit = { ViewfinderFixture(it) },
    val faceOverlay: @Composable () -> Unit = {},
    val onScopePresentation: (List<ScopePresentation>) -> Unit = {},
    /** X14U focus loupe: [x, y, w, h, wx0, wy0, wx1, wy1, corner] in viewfinder fractions; null = off. */
    val onLoupe: (FloatArray?, Boolean) -> Unit = { _, _ -> },
    /** Latest focus meter value (relative; < 0 = none). */
    val focusMeter: () -> Float = { -1f },
    /** A copy of what the viewfinder shows now (half size), or null; delivered on the main thread. */
    val snapshot: ((android.graphics.Bitmap?) -> Unit) -> Unit = { it(null) }
)
