# Xiaomi 14 Ultra / Camera ID5 / YUV + RAW16 admission probe

**Experimental, read-only to system.** No root, no vendor key injection, no changes to stock camera or working RAWR lenses. Debug module `x5-probe` builds as applicationId `com.rawr.camera.x14u.x5yuvprobe`, separately from all current Rawr APKs.

## Why

The proven PhotonVidCam 3x (ID4) trace has opmode 0x900D, repeating YUV 2560x1920 and a one-shot RAW_SENSOR 8192x6144. The failed RAWR 5x was a different one-stream RAW16 layout and did not produce a frame. This probe changes the topology, keeping ID5 and opmode 0x900D, with no speculated vendor keys.

## Exactly one session configuration attempt

- Output 0: Java `ImageReader`, YUV_420_888 2560x1920, repeating target.
- Output 1: Java `ImageReader`, RAW_SENSOR 8192x6144, one-shot target only.
- `SessionConfiguration(36877, ...)` with both stream use cases at their defaults (0).
- Preview request template PREVIEW, still request template STILL_CAPTURE.
- No Xiaomi vendor keys, no fake sensor mode, no SYSTEM_CAMERA access, no auto retry.

**Important limit:** PhotonVidCam's output 0 was labeled `SurfaceTexture` in the CameraService snapshot; this probe uses an `ImageReader` YUV_420_888 surface. The requested format, size, order, targets, and opmode match, but surface type/buffer usage may differ. Success or failure is an observation of THIS topology, not a general result for all possible client surfaces.

## Procedure

1. Install the debug APK alongside Rawr. Open **X14U 5x YUV+RAW Probe**.
2. Tap `1. START ONCE`, allow Camera permission. If `FAIL stage=configure` appears, stop. Do not repeat the exact same test.
3. If `PREVIEW_RUNNING` appears, tap `2. CAPTURE ONE RAW` ONCE. Wait for `RAW_SAVE_OK` or `FAIL` before leaving.
4. Artifacts in `/sdcard/Download/X14U_X5_YUV_PROBE/`: a `*.raw16le` contiguous little-endian RAW_SENSOR plane (100663296 bytes) and `*_metadata.json` with row stride, pixel stride, exposure, CFA and sampled first/last row occupancy. Or a `*_FAIL_*.txt`.
5. Capture `adb logcat` and `dumpsys media.camera` synchronized with test, then send LOGS ZIP and any RAW + JSON.

## Evidence hierarchy

- Session configure success is only proof of acceptance.
- `STILL_SUBMITTED` is not frame arrival.
- `RAW_IMAGE_RECEIVED` proves buffer delivery only.
- `RAW_SAVE_OK` plus full-size, row coverage and sensor metadata is preliminary evidence; actual Bayer must be validated before FullRes is confirmed.

Do not import any lens pack or modify the original RAWR setup.
