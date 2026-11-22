# Xiaomi 14 Ultra (aurora Global) — 5x FullRes portability lab

Status: source patch only; not yet built or tested on the phone. Based on GalleryFix commit `e4cc18f77d646233df256dca3540bd7681f6a7eb`, which the owner reports resolves the Xiaomi Gallery crash and retains 1x/3x FullRes.

## Grounded constraints
- Target: Xiaomi 14 Ultra `24030PN60G`, Global `V816.0.12.0.UNAMIXM`, Android 14. Camera ID5 = periscope IMX858, 19.4 mm, in this device's dump.
- `3x` FullRes + AF + DNG are owner-confirmed on the separately installed GalleryFix build. This is **not** proof that 5x has the same readout route.
- Existing 5x `8192x6144` buffers from PhotonVidCam / Unspektrawesome sometimes contain 12.58M RAW values and an all-zero tail (roughly 25% of the declared plane). A large reader is not a pass.
- Existing user 5x profile is disabled in the newer configuration. We do not change it or overwrite the GalleryFix package.
- The prior RAWR backup has 3x 50MP = RAW16 8192x6144 with PRIVATE 640x480 untargeted, `0x100003`, `xiaomi.remosaic.enabled` byte=1 on session and request; prior 5x 50MP used a different RAW10 + aux JPEG route that the fork author removed.
- The OEM table in the collected Global documentation calls operation modes `0x900D=ManualRaw` (36877) and `0x900E=ManualHdRaw` (36878, `isRemosaic:true`). These are **session operation modes**, not sensor current_mode values.

## Experimental branch
- Side-by-side package: `com.rawr.camera.x14u.5xlab`.
- Built-in `x14u_5x_lab` profile matches only model `24030PN60G` (not claimed for other firmware).
- Lens `5x`: RAW10 12MP viewfinder; option `FullRes` has:
  - `12 MP` — no FullRes request;
  - `900D` — run-as `5x_900D`;
  - `900E` — run-as `5x_900E`.
- Both experiment targets: direct ID5; RAW16 8192x6144 highResLive; AHB usage `0x100003`; untargeted PRIVATE 640x480 companion; Java Camera2; still template, startup 500 ms, requested frame rate 15; `xiaomi.remosaic.enabled` byte=1 on session and request (transferred as a **hypothesis** from the confirmed 3x route). Only the session opmode differs between 900D / 900E.
- The profile is **not** proof that Camera2 accepts either custom mode or delivers usable 50MP Bayer. Do not add arbitrary sensor mode indices.
- The native JSON serializer drops option rows; the Kotlin `DeviceLensDefaults.kt` adapter attaches the run-as selection specifically to `x14u_5x_lab`.
- DNG `FixBadPixelsList` remains omitted, as in the successful GalleryFix build. Original `com.rawr.camera.x14u` and `com.rawr.camera.x14u.galleryfix` remain installed and untouched.

## Single controlled on-device series
1. Start 5x Lab app. Keep `FullRes=12 MP` on launch; verify normal 5x preview once. Do not alter original apps.
2. Select `FullRes=900D`. Shoot **one** still, note failure or DNG and timestamp. If Camera HAL crashes, stop rather than auto-retrying.
3. Select `FullRes=900E`. Under otherwise identical settings, shoot **one** still only if camera service is stable. Capture unfiltered logcat plus filtered evidence.
4. For 5x, verify actual image/plane dimensions, stride, full-plane coverage (including below row 1536), CaptureResult timestamps and CFA/detail. `8192x6144` metadata or `DNG saved` alone is not success.
5. If both fail in the same way, next change must isolate a new cause (e.g. request-scoped keys or RAW buffer transition). Do not restart the known-failed Route2 or 5x single large buffer sweep.

## Cross-application transfer matrix (after 5x sensor route passes)
| Target | What can transfer | What must not be assumed |
|---|---|---|
| RAWR X14U GalleryFix | 12MP viewfinder / run-as / 50MP still / optional opcode suppression; package profile schema 2 | Do not replace the working GalleryFix settings; changes go in independent branch/build |
| RAWR debug / Aurora | Camera2 session lifecycle, stream topology, AF carryover, RAW geometry and DNG metadata mapping | Fork-specific lens import formats and camera id aliases may differ |
| PhotonVidCam 0.98 build 4389 | Existing imported ID5 A/B configs `36877` vs `36878`; log exact SessionConfiguration and Image.Plane | Current `dev` source is not identical to installed build, and per-lens overrides win over global |
| RawLens | Only vendor keys proved in its exported schema and exact Camera2 scopes; preserve the working lens pack | Do not claim a RAWR JSON is a RawLens import ZIP; no verified importer schema bundled here |
| Unspektrawesome 1.3.3 | RAW16 AImage input sampling, exposure/AF handover, sensor geometry logging | 5x 50MP has previously produced partly empty buffers; writing an 8192-wide DNG does not fix missing RAW |
| Xiaomi Camera stock | Reference for possible internal ISP/CHI mode | Rootless third-party Camera2 cannot simply assume stock MiHAL privileges |

Portability success requires the same **camera output semantics** in each program, not identical UI names or numeric mode indices. For example, `sensor_meta_data.current_mode=7` is owner-confirmed DCG4 only on 1x; it must never be copied as a 5x DCG4 value.

## Status ledger
- 1x FullRes, 3x FullRes, GalleryFix: owner-confirmed working in GalleryFix (user report, earlier 1x session log).
- 5x 900D/900E in this Lab APK: not built/tested yet.
- 5x genuinely independent 50MP Bayer: unverified.
- Cross-app porting: planned with evidence gates, not yet implemented.
