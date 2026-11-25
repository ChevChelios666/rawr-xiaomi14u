#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
mkdir -p tests/build
javac --release 17 -d tests/build \
 source/com/particlesdevs/photoncamera/processing/Raw16SampleAudit.java \
 source/com/particlesdevs/photoncamera/processing/PhotonRaw50AuditHooks.java \
 tests/Raw16SampleAuditTest.java \
 tests/android_stubs/android/graphics/ImageFormat.java \
 tests/android_stubs/android/media/Image.java \
 tests/android_stubs/android/util/Log.java
java -cp tests/build com.particlesdevs.photoncamera.processing.Raw16SampleAuditTest
python3 tests/test_overlay.py
