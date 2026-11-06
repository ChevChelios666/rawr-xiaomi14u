package com.rawr.camera.ui

import com.rawr.camera.registerComposeTestActivity

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.rawr.camera.fixtures.CaptureFixtures
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.DcpQuickState
import com.rawr.camera.model.LensOptionsQuickState
import com.rawr.camera.settings.model.LensDcp
import com.rawr.camera.settings.model.LensOption
import com.rawr.camera.settings.model.LensOptionChoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** X14U build: DCP is a pill beside FILM; the second lens row holds option slots that never move. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class CaptureDcpSelectorTest {
    init { registerComposeTestActivity() }

    @get:Rule val compose = createComposeRule()
    private val first = LensDcp("0".repeat(32) + ".dcp", "A long daylight camera profile name")
    private val second = LensDcp("1".repeat(32) + ".dcp", "Tungsten")

    @Test fun dcpPillSelectsProfilesAndDefault() {
        val dcp = mutableStateOf(DcpQuickState("wide", listOf(first, second), first.file))
        var longPressed = false
        compose.setContent {
            CaptureTheme {
                DcpSidePill(dcp.value, { dcp.value = dcp.value.copy(selectedFile = it) }) { longPressed = true }
            }
        }
        compose.onNodeWithTag(CaptureTestTags.DCP_PROFILE).assertIsDisplayed().performClick()
        compose.onNodeWithTag(CaptureTestTags.dcpOption(second.file)).performClick()
        compose.runOnIdle { assertEquals(second.file, dcp.value.selectedFile) }
        compose.onNodeWithTag(CaptureTestTags.DCP_PROFILE).performClick()
        compose.onNodeWithTag(CaptureTestTags.dcpOption(null)).performClick()
        compose.runOnIdle { assertEquals(null, dcp.value.selectedFile) }
        compose.onNodeWithTag(CaptureTestTags.DCP_PROFILE).performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(true, longPressed) }
    }

    private val aperture = LensOption(
        "Aperture",
        listOf(LensOptionChoice("f/1.67"), LensOptionChoice("f/2"), LensOptionChoice("f/4")),
        selected = 1
    )
    private val dcg = LensOption("DCG", listOf(LensOptionChoice("Off"), LensOptionChoice("On")), toggle = true)

    @Test fun optionSlotsStayPutAndGreyOnLensesWithoutThem() {
        val base = CaptureUiState(capabilities = CaptureFixtures.baseline())
        val selected = base.selectedLensId
        val options = mutableStateOf(listOf(LensOptionsQuickState("elsewhere", listOf(aperture, dcg))))
        compose.setContent {
            CaptureTheme {
                LensAndDcpSelector(base, {}, Modifier.width(336.dp).testTag("lens_rows"), lensOptions = options.value)
            }
        }
        val rowBefore = compose.onNodeWithTag("lens_rows").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag(LensOptionTags.chip("Aperture")).assertIsNotEnabled()
        compose.onNodeWithTag(LensOptionTags.chip("DCG")).assertIsNotEnabled()
        compose.runOnIdle { options.value = listOf(LensOptionsQuickState(selected, listOf(aperture, dcg))) }
        compose.onNodeWithTag(LensOptionTags.chip("Aperture")).assertIsEnabled()
        compose.onNodeWithTag(LensOptionTags.chip("DCG")).assertIsEnabled()
        assertEquals(rowBefore.height, compose.onNodeWithTag("lens_rows").fetchSemanticsNode().boundsInRoot.height)
    }
}
