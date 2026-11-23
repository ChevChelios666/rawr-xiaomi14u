# Xiaomi 14 Ultra 5x — independent FullRes still stream (2026-11-23)

**Status**: experimental source only, no confirmed phone capture. Forked from the owner-confirmed GalleryFix commit `e4cc18f77d646233df256dca3540bd7681f6a7eb`. Side-by-side app ID: `com.rawr.camera.x14u.5xdual`. Does not replace original `com.rawr.camera.x14u`, GalleryFix, nor any other installed lens profile.

## Goal and source-backed constraints
- Exact device: Xiaomi 14 Ultra `aurora`, `24030PN60G`, Global Android 14 `V816.0.12.0.UNAMIXM`.
- **First priority**: actual independent sensor data across **8192×6144 Bayer RAW** on ID5 (IMX858 periscope).
- **Second**: working AF and reliable single still. **Third**: multiple full-size RAW frames for existing Rawr stacking.
- Preview quality, resolution, fluency, and continuity are expendable.
- Owner reports 1x and 3x true FullRes + focus and Gallery compatibility already work in the GalleryFix APK. Do not retest or alter those successful packages.
- Earlier 5x attempts recorded `8192×6144` buffers with meaningful data in only roughly the first 1/4 (12.58 MP). Neither reader dimensions, metadata labels nor a 96 MiB output establishes real 50 MP.
- Direct ID5 RAW16 `4096×3072` is known from device dumps; it is the preview/AF stream (12.58 MP), **not** a request for a nice preview.
- The OEM `0x900E` / `0x900D` names refer to **session opmodes** (`ManualHdRaw`, `ManualRaw`). They do not map to user-confirmed `sensor_meta_data.current_mode` indices for the 1x camera.
- No speculative session/request vendor keys are copied from 3x. `xiaomi.remosaic.enabled` on 5x is **not assumed** merely because it worked on 3x.

## Pipeline
One Camera2 session with two configured RAW16 outputs:

```
camera ID5 ─┬─ repeating RAW16 4096×3072 → RAW-based viewfinder, AF/AE
            └─ still-only RAW16 8192×6144 → photo reader → DNG
```

Only the still output is targeted by the still capture request (`streams.photo.includePreview=false`). The still sequence is direct, not `runAs`: it avoids the full-size preview handover and timeout of the earlier 3x strategy.

Profile: `app/src/main/cpp/camera/profiles/x14u_5x_dual_still.json`
- `5x-12` 12MP baseline (no separate photo)
- `5xD-900E` dual output, `session.opMode=36878`
- `5xD-900D` dual output, `session.opMode=36877`
- `5xD-std` dual output, `session.opMode=0`

The three dual modes are identical apart from the session opmode. RAW16 4096×3072 preview and RAW16 8192×6144 still, 2 photo reader buffers, AHB usage `0x100003`, `includePreview=false`, `mergeFrameLimit=1`, companion off, Java backend, still request template, 15 fps requested.

**Caveat**: two outputs are configured simultaneously even though the photo output is targeted only at capture time. The Xiaomi HAL may reject their combination. If so, it does not refute the ability to capture true FullRes with a one-output session — the fallback is 12MP AF → stop/close preview session → one FullRes-only still session.

## One-pass test (one APK)
- Use existing GitHub Actions workflow to build **this branch only**, install side-by-side. Confirm package ID in the APK before installing.
- Open `5x-12`, verify AF works. No photo necessary.
- Open `5xD-900E`, focus, request **one** DNG; note saved / camera error / freeze. Do not repeat after HAL failure.
- If still stable, try `5xD-900D` once. Optional `5xD-std` only if both 900E/D fail cleanly with stable camera HAL.
- Keep continuous unfiltered logcat privately, provide filtered zip with `CAMERA_NDK_SELECTION`, `CAMERA_NDK_SESSION_RUNNING`, `CAMERA_NDK_SESSION_FAILURE`, `CAMERA_STILL_TARGETS`, `RAW_AIMAGE_DESCRIPTION`, `DNG_TIMING`, CamX errors and app crashes.
- Verify both RAW geometry and complete pixel plane, especially rows after 1536; only then label FullRes CONFIRMED. AF result must be correlated with the capture timestamp.
- Single DNG must work before testing multiframe; remove the `mergeFrameLimit=1` limit in a **subsequent controlled build** if successful.

## Transfer to other apps after positive ID5 capture
Document exact session configuration / output surfaces / AF state / result / buffer layout, then adapt individually:
- **RAWR/Aurora**: session creation, still target, AF carryover, DNG writer; preserve existing packages.
- **PhotonVidCam 0.98/4389**: adaptation against actual APK build; prior 900D/E experiment prepared. Do not assume dev-source method names match.
- **RawLens**: needs proven import schema from a successful export; never substitute a RAWR JSON.
- **Unspektrawesome**: must measure source Image.Plane occupancy before copying; prior 5x DNGs contain 12MP + zeros.
- **Stock Xiaomi Camera**: reference only. No root/bootloader modification.

`CONFIRMED` 1x/3x/GalleryFix owner-reported; `UNVERIFIED` 5x dual stream, true 50MP, and cross-app portability.
