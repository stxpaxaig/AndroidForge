package com.ghosttap.core.ops

import com.ghosttap.core.OpResult

/**
 * Seed registrations (Package 0): the six existing function screens as
 * open-screen operations, so the "pick what you want to do" screen is
 * real on day one. Each operation only opens its screen — the screens
 * themselves are untouched. Area packages (1–7) register the rest.
 *
 * Registration is idempotent: calling this again keeps the same six
 * definitions. The plain-words titles here are NEW labels for the
 * entry screen; the six main-screen buttons keep their original
 * wording, and each description still names the original screen so
 * the user recognises where they are going.
 */
fun registerSeedOperations() {
    OperationRegistry.register(
        screenOperation(
            id = "screen.usb_uart",
            title = "Talk to a wired board",
            description = "Opens USB UART / ESP32 — talk to a board plugged into the phone by USB cable.",
            area = OperationArea.USB,
            route = "usb_uart"
        )
    )
    OperationRegistry.register(
        screenOperation(
            id = "screen.vesp32",
            title = "Use the practice board",
            description = "Opens VIRTUAL ESP32 — the pretend board, no real board needed.",
            area = OperationArea.USB,
            route = "vesp32"
        )
    )
    OperationRegistry.register(
        screenOperation(
            id = "screen.network_tools",
            title = "Check what's on my Wi-Fi",
            description = "Opens NETWORK TOOLS — find devices on your Wi-Fi and talk to them.",
            area = OperationArea.NETWORK,
            route = "network_tools"
        )
    )
    OperationRegistry.register(
        screenOperation(
            id = "screen.nfc_tools",
            title = "Read or write a tap card",
            description = "Opens NFC TOOLS — read, write, copy, and pretend to be tap cards.",
            area = OperationArea.NFC,
            route = "nfc_tools"
        )
    )
    OperationRegistry.register(
        screenOperation(
            id = "screen.visual_intel",
            title = "Point the camera at something",
            description = "Opens VISUAL INTEL — read words and barcodes from boards, chips, labels, and devices.",
            area = OperationArea.VISUAL,
            route = "visual_intel"
        )
    )
    OperationRegistry.register(
        screenOperation(
            id = "screen.investigations",
            title = "Look into something",
            description = "Opens INVESTIGATIONS — step-by-step checks of things Ghost Tap found.",
            area = OperationArea.INVESTIGATIONS,
            route = "investigations"
        )
    )
}

/**
 * One screen-first operation: no prerequisites, no preparation — the
 * execution opens the route through the shared context, and the
 * verification is the default one (the navigation call returned
 * without failing). Opening a screen changes nothing on any device,
 * so this is READ risk and not hardware-verified.
 */
private fun screenOperation(
    id: String,
    title: String,
    description: String,
    area: OperationArea,
    route: String
): Operation = Operation(
    id = id,
    title = title,
    description = description,
    area = area,
    execute = { context ->
        context.navigate(route)
        OpResult(true, "Opened $title.", route)
    },
    riskLevel = "READ",
    verified = false,
    route = route
)
