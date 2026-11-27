# X14U Hybrid 3x Phase 1 — isolated Camera2 admission prototype

**Date**: 2026-11-27
**Base**: `lab/galleryfix-20261122-152959` (GalleryFix preserved; original branches unchanged).
**Status**: Source prototype only until Android CI and real device logs succeed.

## Goal

Enable an explicit **single RAW output** session topology on **Camera ID 4 (3x)**:
- YUV_420_888 `2560×1920` companion output (repeating, buffers drained), for continuous camera AE/AF;
- RAW_SENSOR `8192×6144` one-shot `photoStream`, for still capture;
- session opmode `0x900D` (36877);
- **zero** 4096×3072 RAW-viewfinder output.

This is an **admission-only** stage. It intentionally does **not** render the YUV viewfinder to the screen: the on-screen view may be black. Real processed preview rendering is Phase 2.

## Scope controls

The experiment is disabled unless `debug.rawr.x14u_hybrid3x=1` AND the selected route has:
- `cameraId="4"`, no `physicalCameraId`;
- `photoStream=RAW16 8192×6144`;
- session `opMode=0x900D`.

The profile/lens format remains the genuine RAWR profile format; existing profiles are **not edited**. Other IDs, other opmodes, ordinary RAW preview, GalleryFix and any installed camera apps are unaffected. Experimental APK applicationId is `com.rawr.camera.x14u.hybrid3xlab`.

## What changed in source

1. `CameraRouting/SessionRecipe.h`: experimental `hybrid3xAdmissionOnly` flag forces YUV companion, disables raw preview in still capture and requires fixed 2560×1920. Only intended for one optional route.
2. `NativeCameraController.cpp`: bypasses creation of binned RAW input window only in hybrid route; retains normal RAW50 capture window, no automatic RAW-only fallback when YUV is required; emits `X14U_HYBRID3X_PHASE1_BEGIN`.
3. `CameraDeviceSession.cpp`: creates two outputs only (YUV companion and RAW50 capture), repeats YUV only, still request includes RAW50 plus companion, sends `X14U_HYBRID3X_TOPOLOGY`.
4. Separate debug application ID and name to protect the working GalleryFix installation.

## Known limitations / failure conditions

- **No actual live image in viewfinder** yet: companion ImageReader is drained, not presented. A black viewfinder is expected, not evidence of success.
- A working RAWR experimental *photoStream* route must exist before enabling. Simply selecting stock 3× RAW12 does nothing.
- Reader allocation, `onConfigured` or a DNG header do not prove native FullRes Bayer. Need actual CameraService stream map and delivered buffer with rowStride, pixelStride, timestamp, zero map and CFA inspection.
- Xiaomi may admit 8192×6144 only for package `com.xiaomi.module.evaluation`. The lab APK has a different package. If CameraService refuses this source prototype, distinguish package admission from topology before further changes.
- No root/ROM/vendor property change is included or required.

## On-device safety and rollout plan

Do **not** install or change any working camera yet. First validate Android CI. If CI passes, create a proper experimental RAW50 3× lens **separately** and an ADB one-run logger with a hard guard on the output topology. Do not set experimental properties blindly or use the test package to replace PhotonVidCam.

## Required success criteria

- `X14U_HYBRID3X_TOPOLOGY` emitted for direct camera 4.
- CameraService for this one app shows **YUV 2560×1920 + one RAW_SENSOR 8192×6144** without binned RAW stream.
- Session configured; metadata callbacks show AF states and repeating YUV frame cadence.
- Still capture submit confirmed (not merely reader creation).
- Full RAW Bayer if delivered must pass buffer size, per-quadrant zero map and CFA validation.

**If session admission fails, Phase 2 YUV rendering is unnecessary until admission/identity cause is isolated.**
