# X14U Camera Lab — PhotonVidCam 5× RAW50: інструментація до DNG

**Дата:** 25.11.2026 · **Пристрій:** Xiaomi 14 Ultra `aurora`, Global `V816.0.12.0.UNAMIXM` · **робочий застосунок:** PhotonVidCam `0.98 / versionCode 4389`, `com.xiaomi.module.evaluation`.

## Мета

Встановити місце втрати 75% Bayer-відліків: **до ImageReader**, **під час копіювання ImageReader → ImageFrame**, чи **між ImageFrame та DNG encoder**. Зберегти незмінними робочий PhotonVidCam, RAWR, RAWLens і всі профілі.

## Підтверджені дані

1. **CameraService, 19:18:36, Camera ID 5:** `0x900D`, `RAW_SENSOR 8192×6144`, `ImageReader` отримав один frame. Це підтверджує доставку потоку, але не цілісність 50 МП Bayer.
2. **Піксельний аудит отриманого DNG:** 50 331 648 відліків у контейнері; **12 582 912** ненульових і **37 748 736** нульових (75%). Перші 12 582 912 утворюють 4096×3072 растр, дані не можна відновити до справжнього 50 МП простим перепакуванням.
3. **Лог:** `ERROR_CAMERA_BUFFER` code 5, пов'язаний з frame 403. `MiCamHAL` також повідомив помилку internal quickview. Який саме вихідний buffer отримав помилку — невідомо.
4. **V6:** намагання з `0x900E` завершилося `configureStreamsLocked -38`, але фактичний opmode невдалої сесії не підтверджений; жодного кадру V6 не було.

## Прив'язка до вихідників

У **публічній гілці `dev`** PhotonVidCam підтверджено:

`CaptureController.mOnRawImageAvailableListener → ImageSaver.initProcess → RAW16Saver.addImage → SaverImplementation.getFrame → ImageFrame(Allocator.allocateAndCopy) → ImageSaver.Util.saveSingleRaw → DngCreator`.

Приклади потенційно проблемних місць:

- `SaverImplementation.getFrame()` створює `ImageFrame` з `image.getPlanes()[0].getBuffer()` і обчислює `frame.width = rowStride/pixelStride` (не завжди тотожно `image.getWidth()`, якщо є padding).
- `ImageFrame` викликає нативний `Allocator.allocateAndCopy(in.capacity(), in)`, який копіює `capacity` із початку direct buffer (`GetDirectBufferAddress`) без перевірки `limit`, позиції та фактично валідної області.
- `DngCreator` записує буфер за розмірами `width × height`, без row-stride unpacking.

**Обмеження:** публічне джерело `dev` **не тотожне** встановленому APK 4389 (наприклад, у публічному коді є шлях `setCompression(false)`, тоді як зразок 4389 збережено як DNG із Lossless JPEG Compression 7). Патч нижче застосовується лише до перевіреного авторами checkout і має бути перенесений в точний release source. Не припускати, що байткод 4389 покроково збігається з `dev`.

## Що готове в пакеті

- `Raw16SampleAudit.java` — чистий Java, не змінює source ByteBuffer; за кроком 8 пікселів збирає `zero/nonzero`, `rowStride`, `pixelStride`, `capacity`, `limit`, `requiredBytes`, CRC32 sampled values, 4 вертикальні смуги і 4 квадранти.
- `PhotonRaw50AuditHooks.java` — Android adapter із тегом logcat **`PVC_RAW50_AUDIT`**.
- `apply_to_photon_source.py` — створює **окремий overlay**, не редагує оригінальний checkout; відмовляє при розбіжностях у вихідниках.
- `tests/` — компіляція `javac`, тест повного буфера, 25%+75% нулів, padding, обрізаного буфера, незмінності ByteBuffer.position, fail-fast overlay.

## Як розробник інтегрує

Використовувати лише джерела, на які має право. Автор PhotonVidCam або особа, що збирає сумісну авторизовану версію:

```bash
python3 tools/apply_to_photon_source.py --source /path/to/PhotonVidCam --out /tmp/PVC_RAW50_OVERLAY
```

Чотири файли overlay потрібно перевірити й перенести у **окрему гілку PhotonVidCam**, підготувати **сумісно підписаний** діагностичний реліз. Програма додає тільки логи, пікселі не змінює, великих RAW-бінарників автоматично не експортує.

**НЕ встановлювати збірку з іншим підписом поверх `com.xiaomi.module.evaluation`**. На Android не можна безпечно паралельно встановити той самий пакет з іншою сигнатурою. Не видаляти робочу версію заради експерименту. Користувач вирішує про оновлення після перевірки підпису, резервного копіювання налаштувань і наявності способу відкату.

## Інтерпретація одного кадру

| `BEFORE_COPY` | `AFTER_COPY` | `BEFORE_ENCODER` / DNG | Інтерпретація |
|---|---|---|---|
| ~25% значень активні | так само | ~25% | Неповний буфер до копіювання (HAL / gralloc / sensor path — точний рівень ще невідомий) |
| Повний у всіх смугах | ~25% | ~25% | Копіювання/Allocator порушує дані |
| Повний | Повний | ~25% | Помилка шляху між копією і writer |
| Повний | Повний | Повний, DNG 75% нулів | DNG-encoder/розкладка/стискання/метадані |

**Увага:** інструмент використовує sampling (step 8), не перебирає весь RAW, не підтверджує native Bayer в кожній точці. Для остаточного результату потрібен повний RAW buffer dump із дозволеного debug build або перевірка повної піксельної структури і метаданих. Нульові RAW-відліки самі по собі можливі; тут тестуємо аномальні суцільні порожні смуги, а не будь-які одиничні нулі.

## Поточний статус

- `RAW50 ID5 session and one output buffer: CONFIRMED BY LOG`.
- `DNG FullRes pixel integrity: FAILED`.
- `Location of zero-padding: UNVERIFIED`.
- `Diagnostic helper Java 17 compile and synthetic tests: PASS`.
- `Android debug APK built with original author signing identity: NOT BUILT`.
- `On-device RAW50 data before saver: NOT MEASURED`.
