# Ghost Tap — ChatGPT-controlled intelligence branch

This branch is isolated from `main` and from Muse's VirtualESP32 handoff branch.

## Source baseline

- Muse fixed3 archive: `ghosttap-ai-command-center-fixed3.zip`
- Source archive SHA-256: `61cd7ee0707a1bf9750f6333b45cd247ccaf7b870b0de4429358a21aa2b3fbc2`
- Original archive: 558,779 bytes, 176 ZIP entries.
- Temporary source URL was supplied by the project owner and is used only for the first CI bootstrap. The workflow snapshots the patched full project under `ghosttap-source/` so future builds no longer depend on the temporary link.

## Intelligence repair in this overlay

1. Reuses the existing THINKING ENGINE endpoint and API key for VNIC at request time.
2. Routes fresh VNIC hypotheses and capability planning to OpenAI when a key is saved.
3. Does not silently substitute local keyword rules or old memory when a configured OpenAI request fails.
4. Requires fresh research for every non-context observation, even when prior memory matches.
5. Sends the live capability registry (all registered operations/capabilities, with descriptions, declared inputs where present, preconditions and effects) to the model.
6. Validates every model-selected capability ID and argument against the live registry before execution.
7. Removes the local transport-keyword pre-filter from autonomous investigation-loop VNIC drive, so unfamiliar nonblank targets reach AI classification/research.
8. Adds regression tests for fresh research, dynamic key selection, explicit remote failure and unknown-target autonomous eligibility.

The patch is stored as base64-compressed unified diff at `ghosttap-intelligence.patch.gz.b64`; its decoded gzip SHA-256 is `21ef9ef160b7e2c379db6edee2bd8e9bcc8421f2935a7f761f3d2437be13f26d`.

## Build

The branch workflow downloads and hash-verifies the original fixed3 ZIP once, applies the overlay, snapshots the patched project to `ghosttap-source/`, runs JVM unit tests and builds the debug APK. Future builds use the source committed on this branch, not Muse or the temporary URL.

No API key is stored in this branch. The only key source is Ghost Tap's existing local THINKING ENGINE setting.
