Xiaomi 14 Ultra - ID4 vs ID5 Same-APK Admission Control V3

GOAL: distinguish a camera-ID-specific FullRes RAW session failure from a failure of this
probe's YUV ImageReader topology / package identity.

This is NOT AGC. It changes no stock or RAWR profiles. It does not use root.

ONE RUN:
1. Unzip to a separate folder on Windows Desktop.
2. Connect authorized ADB WiFi (one Xiaomi 14 Ultra only).
3. Double click RUN.cmd. APK is installed/updated as the separate diagnostic package.
4. First stage on phone: header ID4 CONTROL V3. Tap START ONCE; if PREVIEW_RUNNING
   tap CAPTURE ONE RAW. If FAIL, do NOT repeat. Press Enter on PC.
5. Second stage: header ID5 CONTROL V3. Same actions, then Enter.
6. Upload X14U_ID4_ID5_CONTROL_...zip from Desktop. If any RAW_SAVE_OK,
   also share its raw16le and metadata.json from Downloads/X14U_X5_YUV_PROBE.

IMPORTANT: if install fails due to a signing conflict, this script never removes
apps. Only the experimental package com.rawr.camera.x14u.x5yuvprobe may be
uninstalled if needed; production RAWR and Xiaomi stock camera must be left alone.

OUTCOMES:
ID4 PASS / ID5 FAIL: evidence of ID5-specific admission vs known 3x counterexample.
ID4 FAIL / ID5 FAIL: same APK topology/Surface may be a confounder; compare with
PhotonVidCam SurfaceTexture and exact CameraService log before any new patch.
ID4 PASS / ID5 PASS: inspect original RAW buffers, metadata, Bayer occupancy and AF.

No raw images are pulled into the logs ZIP by default (avoid 100MB transfers).
