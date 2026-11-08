#pragma once

#include "encoding/dng/DngCaptureContext.h"
#include "tonemap/TonemapMath.h"

namespace rawrcam::encoding::dng {

// X14U: the render's final tone for StopBath's spektra.rawr (aePostGain as the render uses it).
inline DngFilmTone filmToneFrom(const tonemap::TonemapParams& tone, float aePostGain) {
    DngFilmTone t;
    t.aePostGain = aePostGain;
    t.renderExposureEv = tone.exposureEV;
    t.sceneKeyEv = tone.adaptiveTone.keyEv;
    t.highlightAnchorEv = tone.adaptiveTone.highlightEv;
    t.shadowLiftEv = tone.adaptiveTone.liftEv;
    t.localToneShadowsEv = tone.localTone.shadowsEv;
    t.sceneKeyMetering = tone.adaptiveTone.liftEv > 0.0f;
    return t;
}

}  // namespace rawrcam::encoding::dng
