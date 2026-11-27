# SCAM RAW50 Lab (Xiaomi 14 Ultra) — Camera2 instrument

**Source-based reconstruction informed by the supplied SCAM TEST 1.13 / SCAMERA 0.98 APKs.** This is **not** modified original SCAM nor SCAMERA. The two original APKs and their working profiles are unchanged.

## Architecture

- Camera ID 4 (3x) or Camera ID 5 (5x), direct `openCamera()`.
- `SessionConfiguration(36877 / 0x900D)`.
- `YUV_420_888 2560×1920` preview reader (continuously drained), one `RAW_SENSOR 8192×6144` ImageReader (`usage=3`, `maxImages=2`).
- PREVIEW repeating target only YUV; one STILL_CAPTURE request targets one RAW output.
- Variant A control: no vendor session parameters.
- Variant B: changes **only** advertised session key `org.codeaurora.qcamera3.sessionParameters.overrideResourceCostValidation` to `byte[]{1}`. If key is missing or type differs, refuses B (no guessing). The SCAM TEST 1.13 dump found this exact name and `byte[]` type, but its effect on this camera is **unverified**. We do not infer that presence means it enables FullRes.
- Before DNG/any encoding: samples 1/16 RAW16 sites in each quadrant; reports nonzero %, CRC32, row/pixel strides, sample range, timestamps, and saves 4 × 64KiB raw segments. There is **no DNG writer** in this first diagnostic build, so it cannot produce a misleading 50 MP DNG.

## Known differences from successful PhotonVidCam session

Successful PhotonVidCam used an active `SurfaceTexture`/processed preview with HAL format `0x23` and usage `0x20100`, whereas this diagnostic uses `YUV ImageReader`. Thus even a failed B session cannot isolate package identity from preview-consumer difference. Prior A-style external probes failed to configure RAW50. B is a **new controlled key variation**, not a guaranteed fix.

## Safety

- Install only as a separate app `com.x14u.scamlab`.
- No root, unlocking, hidden camera-id guessing, or system property writes.
- Stops on error/timeout. No burst or high memory allocation beyond 2 RAW buffers.
- Do not interpret `READERS_ALLOCATED`, `STATIC_IS_SESSION_SUPPORTED=true`, or `SESSION_CONFIGURED` alone as native 50MP Bayer.

## Expected outcome

In app, select **ID4 A** only if a new same-stack baseline is needed (old V3–V5 already failed). Prefer **ID4 B** once. ID5 B only after ID4 result, since 5x had known 75% zeros in original Photon. PowerShell collector saves CameraService/logcat + app evidence. Compare raw quadrant values directly against prior 5x Photon DNG.

## Status

Source created; Android GitHub CI build necessary. **On-device results unverified**.
