X14U AGC 2.0 5x 50MP DNG PROVENANCE - ONE RUN

1. Extract ZIP into a new folder on Windows Desktop.
2. Ensure ONE Xiaomi 14 Ultra (aurora) is connected and authorized in adb devices.
   Wireless Debugging is fine. No root.
3. Double-click RUN_AGC_ONE_RUN.cmd in the extracted folder.
4. On phone, use AGC Camera 2.0, PHYSICAL 5x, 50MP DNG (not stock JPEG).
   Prepare a static detailed scene. Press Enter on PC when ready.
5. When PowerShell says SHOOT NOW, capture ONE AGC 5x 50MP DNG.
   Wait for it to finish saving, then press Enter on PC.
6. OPTIONAL: type YES if AGC allows an ordinary 5x 12MP DNG; take it on the same scene.
   Otherwise press Enter to SKIP. Do not repeat the FullRes test.
7. Upload the Desktop *_LOGS.zip first. *_SAMPLES.zip is optional but useful.

Technical: continuous logcat, 45 short CameraService ACTIVE session snapshots,
full dumpsys before/after, camera mode time marks, media list delta, pulls ONLY NEW files,
file SHA256, ZIP archive validation. It does not erase logcat or photos.

IMPORTANT: This capture can discriminate AGC 12MP input + SABRE vs native FullRes
only if logs/session reveal the source image buffer size or AGC's internal path.
A 50MP DNG alone does not prove native sensor readout.

The FULL logcat may contain unrelated application metadata: review before public sharing.
Windows PowerShell 5.1/ADB behavior needs a real-device check even if CI syntax passes.
