X14U CAMERA LAB — 5x 50MP RAW: 0x900E ManualHdRaw / remosaic test
Global Xiaomi 14 Ultra (aurora), HyperOS V816.0.12.0.UNAMIXM
2026-11-25

МЕТА
Перевірити ОДИН обґрунтований чинник: змінити лише Session Type / OpCode
в уже встановленому PhotonVidCam Xiaomi 0.98 (4389):
поточний 36877 (0x900D ManualRaw) -> тестовий 36878 (0x900E ManualHdRaw).

ЧОМУ САМЕ ЦЕ
У фактичному ODM-файлі mivivendorstreamconfig.json з твого смартфона:
  36877 / 0x900D / "ManualRaw": RAW16, isRemosaic не задано
  36878 / 0x900E / "ManualHdRaw": RAW16, isRemosaic=true
  36876 / 0x900C / "50MUltraRaw": RAW16, isRemosaic=true
Тестуємо 0x900E, бо це найближчий до вже доступного режим із remosaic.
Але vendor-конфіг не гарантує, що 0x900E відкриється для camera ID 5
у сторонньому Camera2-клієнті. Важлива перевірка через CameraService.

ДІЇ (ОДИН ТЕСТ)
1. Розпакуй цей ZIP у звичайну папку на Desktop Windows.
2. Підключи телефон через Wireless ADB, перевір один авторизований пристрій.
3. У робочому PhotonVidCam ЗАПИШИ своє початкове значення Session Type/OpCode
   (у попередньому логу для 5x 36877), за потреби експортуй налаштування.
4. ЗМІНИ ЛИШЕ Session Type/OpCode на 36878 (0x900E) у налаштуваннях
   PhotonVidCam. Це налаштування присутнє у GUI релізів 0.98.2886+.
   Не змінюй vendor tags, Camera ID, stream use case, AF/ISO та RAW format.
5. Вибери фізичний 5x IMX858, FullRes RAW 8192x6144.
6. Запусти RUN.cmd. Він виконає ADB-з'єднання, запише безперервний logcat,
   зніме CameraService і запропонує натиснути Enter, коли видошукач готовий.
7. ПЕРЕД ЗАТВОРОМ PowerShell перевірить у реальному CameraService:
   - ID5 у пакеті com.xiaomi.module.evaluation;
   - active device status;
   - opmode CUSTOM (36878) / 0x900E;
   - RAW ImageReader 8192x6144f20.
   Якщо будь-яка умова НЕ виконана: PREFLIGHT_FAIL, НЕ знімай. Архів логів
   буде створений навіть за відмови. Надішли тільки LOGS.zip.
8. Якщо PREFLIGHT_PASS: зроби ОДИН FullRes RAW кадр 5x.
   Не повторюй при зависанні/помилці. На ноуті натисни Enter.
9. НАПРИКІНЦІ ОБОВ'ЯЗКОВО ПОВЕРНИ початковий Session Type/OpCode 36877
   або попереднє збережене значення в PhotonVidCam.
10. Надішли з Desktop два файли, якщо є:
    X14U_PVC5X_<час>_LOGS.zip — першим;
    X14U_PVC5X_<час>_SAMPLES.zip — DNG.

КРИТЕРІЇ
- PREFLIGHT_PASS: Session opmode 0x900E перевірено, але це НЕ FullRes Bayer.
- Session відхилено: 0x900E/ID5 route недоступний у цьому клієнті.
- RAW50 stream + 1 buffer: усе ще НЕ доказ 50 МП корисних даних.
- DNG: декодуємо весь CFA, перевіряємо ненульові семпли, розташування,
  Bayer pattern, метадані, таймстемпи та відсутність пошкоджених зон.

БЕЗПЕКА
Скрипт: READ-ONLY ADB, без root, без install/uninstall, без rm або logcat -c.
Робочий APK PhotonVidCam, RAWR та лінзи не змінюються. Налаштування
OpCode змінюється вручну лише на один тест із відкатом.

ПЕРЕВІРКА ДЖЕРЕЛ
- Вендорний конфіг: X14U_ID5_CAMERASERVICE_AUDIT_20261124_155149.zip
  -> mivivendorstreamconfig.json, Signature: ManualRaw / ManualHdRaw.
- Логи попереднього 5x: X14U_PVC5X_20261125_191718_LOGS.zip
- Джерело підтвердження UI Session Type: PhotonVidCam v0.98.2886 release:
  https://github.com/AJ732101/PhotonVidCam/releases
