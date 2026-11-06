# VirtualESP32 Corrected Build — Independent Verification

**Scope:** VirtualESP32 only. No Ghost Tap source/build changes, no phone installation, and no deployment.
**Target handoff branch:** `muse/virtualesp32-chatgpt-handoff`
**Report prepared:** 2026-11-06 UTC.

## Result

Muse reports that the corrected VirtualESP32 APK was built successfully through AndroidForge's existing **Build Android APK #151** workflow, run **42143704645**. ChatGPT independently downloaded the resulting GitHub Actions artifact and verified its archive and APK contents.

- Workflow run: https://github.com/stxpaxaig/AndroidForge/actions/runs/42143704645
- Run conclusion: **success**
- Run event/branch: push workflow run on `main` at existing main commit `a2640c67046191584573d533f88ff08ffdcac216`. This was an existing builder run; this report does not claim a new commit to main.
- Artifact: `GHOST-TAP-APK`, artifact ID `13469625826`
- Artifact archive size: **26,272,635 bytes**
- Artifact archive SHA-256: `8a6e0e7dcb3892b60166d3e1de2e5e5e632f77690beeafa7d67fb666048d0fc4`
- Artifact contained one APK named `GHOST-TAP-debug.apk`. Despite the generic artifact filename, the APK is VirtualESP32, not Ghost Tap.

## Independent artifact checks performed by ChatGPT

The artifact ZIP was downloaded from GitHub Actions into the working environment and inspected.

| Check | Result |
|---|---|
| Artifact ZIP SHA-256 | Matches Muse's reported `8a6e0e7dcb3892b60166d3e1de2e5e5e632f77690beeafa7d67fb666048d0fc4` |
| APK size | 82,060,163 bytes |
| APK SHA-256 | `a7c918a8aec332526fb9c9982ebe20b60e91f3ae2a3f697b96385a8c72c6a3f9` |
| Package/class namespace | DEX contains `dev/virtualesp32/` application classes |
| ARM64 native QEMU library | `lib/arm64-v8a/libesp32_qemu_android.so` present; 81,361,464 bytes |
| Native library SHA-256 | `6a1fde2ceea5d9c7c9802fea161824b40dbfb392266aae2a72d2708833694894` |
| ESP32 ROM assets | `assets/esp32-v3-rom.bin` and `assets/esp32-v3-rom-app.bin` present |
| Native session-state getter | `nativeGetSessionState` present in DEX |
| Content-URI firmware IPC markers | `no content URI or legacy path supplied`, `LOAD_FIRMWARE rejected:`, and `importAndInspect` present in DEX |
| Start/reset validation markers | `START failed: runtime did not reach running state` and `RESET failed: no firmware imported` present in DEX |

These checks independently establish the APK artifact identity and expected package contents. They do **not** establish on-device runtime stability.

## Corrected source archive (Muse-reported metadata)

- File: `virtualesp32-corrected.zip`
- Size: **25,928,545 bytes**
- SHA-256: `4d3414c5b15953aa167cb3c1a497bfa78930681c2eb7eb3dd8e019417fbce49e`
- Hosted source archive: https://muse.ai/files/1343851108811859/1121146587190298/tvbt2p3b7itnjbv6eg8g8fv9/virtualesp32-corrected.zip
- Muse reported link expiry: **2026-11-08T01:23:01Z**.

The source ZIP itself was not downloaded or independently hashed by ChatGPT in this verification pass. Its hash, contents, and source diff above are recorded from Muse's build report.

## Engineering changes reported by Muse

- D3: Java runtime status reconciled against native QEMU session liveness using the new JNI `nativeGetSessionState`.
- D4: receiver full restart kept distinct from UI soft reset; failed restart no longer tears down the current guest before validating firmware.
- D5: UART event-bus listener registration made idempotent and registered at app startup.
- D6: reset errors surfaced; firmware revalidated before start; runtime state transitions made more explicit.
- D7: bridge service GPIO errors made consistent with receiver errors.
- Native library rebuilt with the session-state JNI getter; exact native library hash is recorded above.
- Muse's host tests included a MicroPython REPL boot, `help()`, a second REPL command, and a six-minute idle run. These were host-QEMU tests, not Android-device tests.

Full source-level details and limitations are in `CORRECTED-BUILD-REPORT.md`.

## Still not proven / remaining gate

1. **No Android-device field test has been performed with this corrected APK.** Install and test are not authorized or claimed here.
2. Confirm firmware import, START, MicroPython `>>>`, `help()`, and sustained idle operation on the phone.
3. Confirm Ghost Tap → VirtualESP32 content-URI firmware handoff, QUERY_STATUS after stop, UART forwarding, and GPIO read/write behavior.
4. The historical 2026-11-05 guest panic has not been conclusively symbolicated: the exact matching ELF/map pair was not found. Muse's QEMU WAIT/wake-up race explanation is a moderate-confidence hypothesis, not a proven root cause.
5. The corrected source ZIP is hosted externally and is not yet committed as the full Android project to this handoff branch. The branch currently contains selected corrected Java/native source files and reports, not the complete reproducible project tree.
6. The separate `.github/workflows/build-virtualesp32.yml` workflow ChatGPT added expects the authoritative ZIP at `input/virtualesp32-merged-bridge9-repaired.zip`. This corrected build did **not** run through that dedicated workflow; it was produced by the existing AndroidForge builder workflow using Muse's hosted ZIP URL.

## Safety / scope

- `main` was not modified by this report commit.
- Ghost Tap was not rebuilt or modified.
- Neither installed app was touched.
- No corrected APK was installed on the user's phone.
