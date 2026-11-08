#include "encoding/SoftwareName.h"
#include "encoding/dng/DngExif.h"
#include "encoding/LensMetadata.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <limits>
#include <string>
#include <utility>

#include "geometry/LensDistortion.h"
#include "geometry/OrientationTransform.h"
#include "spektrafilm/SpektraFilm.h"

namespace rawrcam::encoding::dng {
namespace {

using rawrcam::metadata::rectWidth;
using rawrcam::metadata::validRect;

std::string tiffAsciiDescription(std::string text) {
    for (char& ch : text) {
        const unsigned char c = static_cast<unsigned char>(ch);
        if (c > 0x7f || (c < 0x20 && c != '\n' && c != '\r' && c != '\t') || c == 0x7f) {
            ch = '?';
        }
    }
    return text;
}

// X14U: the "stopbathSpektra" object of the processing recipe (CaptureRecipe.kt), or empty. The recipe is
// compact org.json output; strings are skipped so braces inside them don't count.
std::string recipeObject(const std::string& recipe, const std::string& key) {
    const std::string marker = "\"" + key + "\":";
    const size_t at = recipe.find(marker);
    if (at == std::string::npos) return {};
    const size_t start = at + marker.size();
    if (start >= recipe.size() || recipe[start] != '{') return {};
    int depth = 0;
    bool inString = false;
    for (size_t i = start; i < recipe.size(); ++i) {
        const char c = recipe[i];
        if (inString) {
            if (c == '\\') ++i;
            else if (c == '"') inString = false;
        } else if (c == '"') {
            inString = true;
        } else if (c == '{') {
            ++depth;
        } else if (c == '}' && --depth == 0) {
            return recipe.substr(start, i - start + 1);
        }
    }
    return {};
}

std::string jsonString(const std::string& text) {
    std::string out = "\"";
    for (const char ch : text) {
        const unsigned char c = static_cast<unsigned char>(ch);
        if (c == '"' || c == '\\') {
            out += '\\';
            out += ch;
        } else if (c == '\n') {
            out += "\\n";
        } else if (c == '\r') {
            out += "\\r";
        } else if (c == '\t') {
            out += "\\t";
        } else if (c < 0x20) {
            char buf[8];
            std::snprintf(buf, sizeof(buf), "\\u%04x", c);
            out += buf;
        } else {
            out += ch;
        }
    }
    return out + "\"";
}

// X14U: non-ASCII UTF-8 as \uXXXX (surrogate pairs above U+FFFF), so the ASCII tag keeps valid, lossless JSON.
// Non-ASCII bytes only occur inside JSON strings, where the escape is legal.
std::string asciiJson(const std::string& json) {
    std::string out;
    out.reserve(json.size());
    for (size_t i = 0; i < json.size();) {
        const unsigned char c = static_cast<unsigned char>(json[i]);
        if (c < 0x80) {
            out += static_cast<char>(c);
            ++i;
            continue;
        }
        const int extra = (c & 0xE0) == 0xC0 ? 1 : (c & 0xF0) == 0xE0 ? 2 : (c & 0xF8) == 0xF0 ? 3 : -1;
        uint32_t cp = extra == 1 ? (c & 0x1Fu) : extra == 2 ? (c & 0x0Fu) : (c & 0x07u);
        bool ok = extra > 0;
        for (int k = 1; ok && k <= extra; ++k) {
            if (i + k >= json.size()) { ok = false; break; }
            const unsigned char cc = static_cast<unsigned char>(json[i + k]);
            if ((cc & 0xC0) != 0x80) ok = false;
            else cp = (cp << 6) | (cc & 0x3Fu);
        }
        if (!ok) cp = 0xFFFD;
        char buf[16];
        if (cp > 0xFFFF) {
            cp -= 0x10000;
            std::snprintf(buf, sizeof(buf), "\\u%04x\\u%04x", 0xD800u + (cp >> 10), 0xDC00u + (cp & 0x3FFu));
        } else {
            std::snprintf(buf, sizeof(buf), "\\u%04x", cp);
        }
        out += buf;
        i += ok ? static_cast<size_t>(extra) + 1 : 1;
    }
    return out;
}

// Full float precision (9 significant digits round-trips a float); JSON has no NaN/Inf.
std::string jsonNumber(float value) {
    if (!std::isfinite(value)) return "0";
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%.9g", static_cast<double>(value));
    return buf;
}

// The preset's filmExposureEv inside the spektra block's settings (org.json output), or 0.
float settingsNumber(const std::string& spektra, const char* key) {
    const std::string marker = std::string("\"") + key + "\":";
    const size_t at = spektra.find(marker);
    if (at == std::string::npos) return 0.0f;
    return std::strtof(spektra.c_str() + at + marker.size(), nullptr);
}

// X14U: spektra.rawr, the per-shot numbers that act on the signal before the film (StopBath reproduces them).
std::string filmToneObject(const DngFilmTone& t, const std::string& spektra) {
    const float lift = std::log2(std::max(t.aePostGain, 1.0e-6f));
    const float effective = std::clamp(settingsNumber(spektra, "filmExposureEv") +
                                           std::min(lift, spektrafilm_native::FilmLook::kMaxFilmLiftEv) +
                                           t.renderExposureEv,
                                       -10.0f, 10.0f);
    std::string o = "{";
    // Auto-exposure post gain (ratio, not EV): scene-linear input is multiplied by it before tone/film; the film gets
    // log2 of it (capped at filmLiftCapEv) added to its exposure, and the adaptive-tone meter sees luma * aePostGain.
    o += "\"aePostGain\":" + jsonNumber(t.aePostGain);
    // Render exposure (EV) of the Linear stage (user's Render exposure plus any multiframe lift): added to the film exposure.
    o += ",\"renderExposureEv\":" + jsonNumber(t.renderExposureEv);
    // Cap on log2(aePostGain) that reaches the film exposure (spektrafilm_native::FilmLook::kMaxFilmLiftEv).
    o += ",\"filmLiftCapEv\":" + jsonNumber(spektrafilm_native::FilmLook::kMaxFilmLiftEv);
    // What the film shader got: settings.filmExposureEv + min(log2(aePostGain), filmLiftCapEv) + renderExposureEv,
    // clamped to [-10, 10]; applied as exp2(this) to the film's raw exposure (SpektraFilmExposure.comp).
    o += ",\"filmExposureEvEffective\":" + jsonNumber(effective);
    // Adaptive tone (shader_common/shaders/adaptive_tone.glsl, rawrAdaptiveToneGain): before the film, linear RGB is
    // multiplied by a gain from AP1 luminance Y * aePostGain. The gain peaks at shadowLiftEv EV of lift
    // kAdaptivePeakBelowKeyEv (1.5 EV) below sceneKeyEv, fades to no lift kAdaptiveToeEv (5 EV) further down, and
    // rolls back to no lift at highlightAnchorEv (log2 scene luminance). shadowLiftEv 0 = off (no gain at all).
    o += ",\"sceneKeyEv\":" + jsonNumber(t.sceneKeyEv);
    o += ",\"highlightAnchorEv\":" + jsonNumber(t.highlightAnchorEv);
    o += ",\"shadowLiftEv\":" + jsonNumber(t.shadowLiftEv);
    // Local tone / exposure fusion (StillRenderSequence) before the film: shadows rendered from a synthetic exposure
    // this many EV brighter, blended region by region. 0 = off.
    o += ",\"localToneShadowsEv\":" + jsonNumber(t.localToneShadowsEv);
    // Whether the scene meter's adaptive tone was active for this shot (shadowLiftEv > 0).
    o += std::string(",\"sceneKeyMetering\":") + (t.sceneKeyMetering ? "true" : "false");
    return o + "}";
}

// X14U: with film on, ImageDescription is JSON for StopBath (Rawr recipe metadata handoff, sections 1-7):
// {"spektraRecipe":1,"capture":{"metadata":{"identity":{...},"spektra":{...,"rawr":{...}}}},"rawrDescription":"..."}.
// StopBath 0.124.0+ reads this key and layout (older builds only read "unspektrawesomeRecipe").
constexpr const char* kStopBathRecipeKey = "spektraRecipe";

std::string stopBathDescription(const std::string& text, const std::string& recipe,
                                const std::optional<DngFilmTone>& tone) {
    std::string spektra = recipeObject(recipe, "stopbathSpektra");
    if (spektra.empty()) return text;
    if (tone) spektra.insert(spektra.size() - 1, ",\"rawr\":" + filmToneObject(*tone, spektra));
    return asciiJson(std::string("{\"") + kStopBathRecipeKey +
                     "\":1,\"capture\":{\"metadata\":{\"identity\":{\"appName\":\"Rawr\",\"appVersion\":" +
                     jsonString(RAWR_VERSION_NAME) + "},\"spektra\":" + spektra + "}},\"rawrDescription\":" +
                     jsonString(text) + "}");
}

std::string exifDateTime(int y, unsigned mo, unsigned d, unsigned h, unsigned mi, unsigned s) {
    char buf[32]{};
    std::snprintf(buf, sizeof(buf), "%04d:%02u:%02u %02u:%02u:%02u", y, mo, d, h, mi, s);
    return std::string(buf);
}

std::string exifOffset(std::int16_t minutes) {
    const char sign = minutes < 0 ? '-' : '+';
    const int absolute = minutes < 0 ? -static_cast<int>(minutes) : static_cast<int>(minutes);
    char buf[16]{};
    std::snprintf(buf, sizeof(buf), "%c%02d:%02d", sign, absolute / 60, absolute % 60);
    return std::string(buf);
}

std::string exifSubseconds(std::uint32_t nanosecond) {
    char buf[10]{};
    std::snprintf(buf, sizeof(buf), "%09u", nanosecond);
    std::string out(buf);
    while (out.size() > 1 && out.back() == '0') out.pop_back();
    return out;
}

// Quantize a float to {round(v*1e6), 1e6} int32 pair (matches legacy writer).
void quantizeSRational(float value, int32_t& num, int32_t& den) {
    constexpr double denD = 1000000.0;
    const double scaled = std::round(static_cast<double>(value) * denD);
    num = static_cast<int32_t>(
        std::clamp(scaled, double(std::numeric_limits<int32_t>::min()), double(std::numeric_limits<int32_t>::max())));
    den = 1000000;
}

void quantizeURational(float value, uint32_t& num, uint32_t& den) {
    constexpr double denD = 1000000.0;
    const double scaled = std::round(std::max(0.0, static_cast<double>(value)) * denD);
    num = static_cast<uint32_t>(std::min(scaled, double(std::numeric_limits<uint32_t>::max())));
    den = 1000000;
}

}  // namespace

void fillDngExif(TinyDngWriteParams& p, const rawrcam::imaging::RawSnapshot& frame,
                 const DngCaptureContext& captureContext) {
    const auto& ctx = *frame.metadata.cameraContext;
    const auto widestAperture = widestLensAperture(ctx.availableApertures);
    tinydng_exif& ex = p.exif;
    if (!captureContext.deviceMake.empty()) {
        p.make = captureContext.deviceMake;
    }
    const auto& displayModel = captureContext.deviceDisplayModel.empty()
        ? captureContext.deviceModel : captureContext.deviceDisplayModel;
    if (!displayModel.empty()) {
        p.model = displayModel;
    }
    auto lens = buildLensMetadata(captureContext.deviceMake, displayModel, frame.metadata.focalLengthMm,
                                       lensIdentityOptics(ctx.availableFocalLengthsMm, widestAperture),
                                       ctx.sensorPhysicalSizeMm, ctx.lensFacing);
    p.lensMake = std::move(lens.make);
    p.lensModel = std::move(lens.model);
    p.software = rawrcam::encoding::kSoftwareName;
    p.description = tiffAsciiDescription(stopBathDescription(
        captureContext.imageDescription.empty() ? rawrcam::encoding::kCapturedWith : captureContext.imageDescription,
        captureContext.processingRecipe, captureContext.filmTone));
    switch (rawrcam::geometry::presentationQuarterTurns(ctx.geometry.sensorOrientationDegrees,
                                                        captureContext.deviceRotationDegrees)) {
        case 1u:
            ex.orientation = 6;
            break;  // RightTop
        case 2u:
            ex.orientation = 3;
            break;  // BottomRight
        case 3u:
            ex.orientation = 8;
            break;  // LeftBottom
        default:
            ex.orientation = 1;
            break;  // TopLeft
    }
    // The sensor pixels stay as captured; the lens profile mirror rides in Orientation.
    ex.orientation = rawrcam::geometry::mirroredOrientation(uint16_t(ex.orientation), ctx.profileMirror);
    if (frame.metadata.exposureTimeNs > 0) {
        const uint64_t us = static_cast<uint64_t>(frame.metadata.exposureTimeNs + 500) / 1000u;
        if (us <= std::numeric_limits<uint32_t>::max()) {
            ex.exposure_time[0] = int32_t(us);
            ex.exposure_time[1] = 1000000;
            ex.has_exposure_time = 1;
            const double seconds = double(frame.metadata.exposureTimeNs) * 1.0e-9;
            if (seconds > 0.0) {
                quantizeSRational(float(-std::log2(seconds)), ex.shutter_speed[0], ex.shutter_speed[1]);
                ex.has_shutter_speed = 1;
            }
        }
    }
    if (frame.metadata.sensitivity > 0 && frame.metadata.sensitivity <= 65534) {
        ex.iso = frame.metadata.sensitivity;
        ex.has_iso = 1;
    }
    if (frame.metadata.aperture && validLensNumber(*frame.metadata.aperture)) {
        quantizeURational(*frame.metadata.aperture, ex.fnumber_rational[0], ex.fnumber_rational[1]);
        ex.has_fnumber = 1;
        quantizeURational(float(2.0 * std::log2(*frame.metadata.aperture)), ex.max_aperture_rational[0],
                          ex.max_aperture_rational[1]);
        // ApertureValue written from the same float path as legacy (unsigned).
        quantizeSRational(float(2.0 * std::log2(*frame.metadata.aperture)), ex.aperture_value[0], ex.aperture_value[1]);
        ex.has_aperture_value = 1;
    }
    if (widestAperture) {
        quantizeURational(float(2.0 * std::log2(*widestAperture)), ex.max_aperture_rational[0], ex.max_aperture_rational[1]);
        ex.has_max_aperture_value = 1;
    }
    if (frame.metadata.focalLengthMm && validLensNumber(*frame.metadata.focalLengthMm)) {
        quantizeURational(*frame.metadata.focalLengthMm, ex.focal_length_rational[0], ex.focal_length_rational[1]);
        ex.has_focal_length = 1;
    }
    if (lens.focalLength35mm) {
        ex.focal_length_35mm = *lens.focalLength35mm;
        ex.has_focal_length_35mm = 1;
    }
    if (frame.metadata.focusDistanceDiopters && *frame.metadata.focusDistanceDiopters > 0.000001f) {
        quantizeURational(1.0f / *frame.metadata.focusDistanceDiopters, ex.subject_distance_rational[0],
                          ex.subject_distance_rational[1]);
        ex.has_subject_distance = 1;
    }
    ex.exposure_mode = uint16_t(frame.metadata.aeMode == 0 ? 1 : 0);
    ex.has_exposure_mode = 1;
    ex.white_balance = uint16_t(frame.metadata.awbMode == 0 ? 1 : 0);
    ex.has_white_balance = 1;
    ex.sensing_method = 2;
    ex.has_sensing_method = 1;
    ex.flash = (frame.metadata.flashState == 3 || frame.metadata.flashState == 4) ? 1 : 0;
    ex.has_flash = 1;
    if (validRect(frame.metadata.scalerCropRegion) && validRect(ctx.geometry.activeArray)) {
        const double fullW = rectWidth(ctx.geometry.activeArray);
        const double cropW = rectWidth(frame.metadata.scalerCropRegion);
        if (cropW > 0.0 && fullW >= cropW) {
            quantizeURational(float(fullW / cropW), ex.digital_zoom_rational[0], ex.digital_zoom_rational[1]);
            ex.has_digital_zoom = 1;
        }
    }
    if (captureContext.wallClockUnixMillis > 0) {
        const time_t localSeconds = static_cast<time_t>(captureContext.wallClockUnixMillis / 1000 +
                                                        int64_t(captureContext.utcOffsetMinutes) * 60);
        struct tm local{};
        if (gmtime_r(&localSeconds, &local)) {
            p.datetime = exifDateTime(local.tm_year + 1900, unsigned(local.tm_mon + 1), unsigned(local.tm_mday),
                                      unsigned(local.tm_hour), unsigned(local.tm_min), unsigned(local.tm_sec));
            p.dateOriginal = p.datetime;
            p.dateDigitized = p.datetime;
            // Legacy writer emits OffsetTime* only when the offset is non-zero.
            if (captureContext.utcOffsetMinutes) {
                p.offsetTime = exifOffset(captureContext.utcOffsetMinutes);
                p.offsetOrig = p.offsetTime;
                p.offsetDig = p.offsetTime;
            }
            const uint32_t ns = uint32_t(captureContext.wallClockUnixMillis % 1000) * 1000000u;
            if (ns) {
                p.subsec = exifSubseconds(ns);
                p.subsecOrig = p.subsec;
                p.subsecDig = p.subsec;
            }
        }
    }
    if (const auto specification = lensSpecification(ctx.availableFocalLengthsMm, widestAperture)) {
        std::copy(specification->begin(), specification->end(), ex.lens_spec);
        ex.has_lens_spec = 1;
    }
}

}  // namespace rawrcam::encoding::dng
