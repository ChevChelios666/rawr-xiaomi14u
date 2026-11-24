XIAOMI 14 ULTRA - X14U ID5 YUV/RAW50 PROBE

ONLY ONE WAY TO RUN:
1. Unzip the ENTIRE archive to its own folder on Windows desktop.
2. Connect Wireless debugging via ADB (the device must show as "device" under adb devices).
3. Double click RUN_X14U_ID5_PROBE.cmd inside that folder.
4. The script verifies APK SHA256, checks device name aurora, installs this NEW separate APK, and opens it.
5. On phone, tap "1. START ONCE" and allow camera permission.
6. If FAIL appears, do not repeat. Return to Windows and press Enter.
7. If PREVIEW_RUNNING appears, tap "2. CAPTURE ONE RAW" once and wait for RAW_SAVE_OK or FAIL.
8. Return to PowerShell and press Enter. The script exports a single *_ALL.zip to Desktop.
9. Send that *_ALL.zip to this chat.

This experiment does not modify RAWR working lenses, GalleryFix or system firmware.
It requests only Camera ID 5, opmode 0x900D, YUV 2560x1920 repeating + RAW_SENSOR 8192x6144 single still.
A created session or image reader is not yet proof of valid FullRes Bayer. Need actual RAW buffer and metadata.
