# PhotonVidCam 4389 / Xiaomi 14 Ultra ID5 — request for signed RAW50 buffer audit

**Purpose:** isolate an actual data-integrity failure for RAW_SENSOR 8192×6144, without changing the original user's working app or adding experimental vendor keys.

**Device/runtime:** Xiaomi 14 Ultra (`aurora`, Global `V816.0.12.0.UNAMIXM`). Installed release `com.xiaomi.module.evaluation`, versionName `0.98`, versionCode `4389`.

### Log-confirmed facts

- 2026-11-25 19:18:36, Camera ID 5 / `0x900D`: CameraService accepted a SurfaceTexture 2560×1920 preview plus RAW_SENSOR `8192×6144` (format 32, combined usage `0x20003`); the RAW stream reported **one output buffer produced**, with sensor timestamp `199767365251446 ns`.
- A saved 8192×6144 CFA DNG contained **exactly 12,582,912 nonzero samples and 37,748,736 zero samples**. The usable prefix forms one 4096×3072 image; a full 50 MP Bayer image is not present.
- Same capture frame 403: Camera2 error code 5 (`ERROR_CAMERA_BUFFER`) and MiCamHAL `returnInternalQuickviewBuffer()return error status`. The failing output stream is not independently identified.
- App printed `Black bar detected, ImageReader resolution does not match captured usable data!!!!`; this is a sensor-info heuristic, not a direct pre-saver pixel scan.
- A later attempted `0x900E` trial failed HAL stream configuration `-38` *without a verified effective failing session opmode*. It is not evidence that `0x900E` was successfully exercised.

### Potential data-copy hazard to audit (NOT confirmed as root cause)

The public PhotonVidCam `dev` branch includes the following path:

`ImageReader.Image → RAW16Saver.addImage → SaverImplementation.getFrame → ImageFrame → native Allocator.allocateAndCopy(...) → ImageSaver.Util.saveSingleRaw → DngCreator`.

`getFrame` derives `frame.width` from `rowStride/pixelStride`. The native allocator uses `GetDirectBufferAddress(origin)` and `memcpy(destination, origin, capacity)` rather than a stride-aware row copy. This can conceal unsupported plane geometry or a bad direct buffer. The DNG writer uses caller-specified width/height without explicitly extracting padded rows. **Do not claim this explains the file until pre-copy data are observed.**

The `dev` source is **not guaranteed binary-identical** to installed 4389; in particular, observed DNG compression differs from a `setCompression(false)` path found in public source. Integration must be reviewed against actual author-maintained release code.

### Prepared source-only diagnostic, verified offline

The attached overlay adds three **read-only** probes with the logcat tag `PVC_RAW50_AUDIT`:

1. `BEFORE_COPY`: immediately upon RAW_SENSOR `Image` access, with `width/height`, `rowStride/pixelStride`, ByteBuffer `position/limit/capacity` and required bytes, sampled zero/nonzero occupancy in four row bands and four 2D quadrants, sampled CRC32, timestamp.
2. `AFTER_COPY`: same audit on the `ImageFrame` ByteBuffer after `Allocator.allocateAndCopy`, while original `Image` is still open.
3. `BEFORE_ENCODER`: audit the staged buffer just before DNG writing; also emit `DNG_GEOMETRY_MISMATCH` if stride-derived width differs from the Image width.

Sampling step is 8, bounded (roughly 0.8 million 16-bit samples for 8192×6144), with no mutation of original buffers or automatic 100 MB file write. The helper and adapter compile with Java 17 plus Android API stubs, and synthetic full/partial/truncated/padded tests pass. **This does not establish runtime validity on the handset.**

### Exact request

Please integrate the hooks into a **developer-authorized, signature-compatible build of `com.xiaomi.module.evaluation`** (or your verified alternative supported by the Xiaomi HAL) with a straightforward backup/rollback path. A differently signed APK cannot be installed beside the working original under the same package name. We must not remove the working app to force a signature conflict away.

One 5× frame under the previously working `0x900D` configuration is enough. Send logs for `PVC_RAW50_AUDIT` around the frame timestamp plus DNG. **Do not** rerun `0x900E` while the effective opmode cannot be verified or CHI returns `-38`.

Decision: if `BEFORE_COPY` has 75% null rows, investigate HAL/gralloc/producer and stream mapping. If `BEFORE_COPY` is complete and `AFTER_COPY` is not, fix allocation/copy/ByteBuffer semantics. If both remain full but the DNG is damaged, fix staging or DNG output. File dimensions, reader creation and `Frames produced: 1` alone do not validate Bayer integrity.
