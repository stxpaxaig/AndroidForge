# ChatGPT Engineering Review — VirtualESP32

## Scope
This review is based on the handoff files and recovered native source previously readable on branch `muse/virtualesp32-chatgpt-handoff`, plus inspection of the APK supplied in this conversation. It does not claim a fresh device reproduction, successful native rebuild, or new APK build.

## Verified from supplied APK
- Contains `dev.virtualesp32` classes including `BridgeCommandReceiver`, `FirmwareEmulatorRuntime`, `FirmwareImageManager`, and `EmulatorUartBridge`.
- ARM64 `libesp32_qemu_android.so` and ESP32 ROM assets are packaged.
- APK ZIP integrity passed.
- SHA-256: `c3516f8b4fb144c87e726f3e3893307cd5876ff65d45491c880ff4ab8baffe24`.
- Package inspection does not establish successful execution of a particular firmware image.

## Priority review items
1. **Fresh crash evidence:** Treat the previously reported UART panic as historical until reproduced or captured in a new timestamped log. Obtain a fresh log from the currently installed build before diagnosing it as current.
2. **Symbolicate exact firmware:** Confirm the ELF/map pair matches the flashed MicroPython image (reported ELF hash prefix: `cc85d995e`), then run Xtensa `addr2line` for PCs `0x40091ccf`, `0x4020881a`, `0x40208870`, and `0x400968dc`.
3. **JNI contract consistency:** Recovered native source uses four-argument `nativeStart(flash, efuse, romDir, uartPort)`; update stale three-argument contract comments and verify Java declarations, JNI exports, and call sites agree.
4. **Runtime state truthfulness:** Clear Java/UI running state when QEMU exits or crashes; do not leave the service reporting a live runtime after native termination.
5. **Reset semantics:** Distinguish receiver RESET (full runtime restart) from UI soft reset (QEMU soft reset). Return an explicit error when no backend is active.
6. **UART listener lifecycle:** Register the event listener once at app/process startup and prevent duplicate registrations across service restarts.
7. **Firmware start validation:** Revalidate the selected/imported image immediately before starting. Define and test behavior when a start command arrives while a runtime is already active.
8. **GPIO contract:** Confirm receiver and service behavior match. Unsupported GPIO reads/writes must return explicit errors rather than success-shaped responses.

## Safe validation sequence
1. Capture a fresh timestamped log from the currently installed VirtualESP32 APK during one controlled firmware start.
2. Record firmware filename, size, SHA-256, and matching ELF/map identity.
3. Confirm runtime state before start, after start, and after stop/crash.
4. Only then make targeted source changes and build a new VirtualESP32 APK. Do not rebuild Ghost Tap as part of this work.

## Build caveat
A prior local Gradle attempt could not retrieve Gradle 8.7 because network access was unavailable. No successful ChatGPT-side APK rebuild is claimed here.
