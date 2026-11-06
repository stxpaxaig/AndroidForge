package dev.virtualesp32;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import org.json.JSONObject;

/** IPC bridge service: exposes the Virtual ESP32 QEMU backend to controller
 * apps (e.g. GHOST TAP) via explicit Intents (commands) and broadcasts (events).
 *
 * NOTE: nothing in the app currently starts this service (only VirtualDeviceService
 * is ever started), so it is effectively dead code — the live IPC path is
 * BridgeCommandReceiver. It is kept, with its READ_GPIO/WRITE_GPIO error surface
 * made to match the receiver's exactly (D7), so it is safe to revive without
 * reintroducing silent -1 values or silently ignored pins. Its ACTION_RESET_GUEST
 * is a SOFT reset (same session), unlike the receiver's RESET_GUEST full restart (D4).
 *
 * Commands (via startService with explicit intent to this service):
 *   ACTION_START_GUEST, ACTION_STOP_GUEST, ACTION_RESET_GUEST,
 *   ACTION_SEND_UART (extra EXTRA_DATA),
 *   ACTION_WRITE_GPIO (extras EXTRA_PIN, EXTRA_HIGH),
 *   ACTION_QUERY_STATUS (extra EXTRA_RECEIVER for ResultReceiver callback),
 *   ACTION_READ_GPIO (extras EXTRA_PIN, EXTRA_RECEIVER)
 *
 * Events (broadcasts, action prefix dev.virtualesp32.event.):
 *   EVENT_GUEST_STARTED, EVENT_GUEST_STOPPED, EVENT_GUEST_RESET,
 *   EVENT_UART_RX (extra EXTRA_DATA),
 *   EVENT_GPIO_CHANGED (extras EXTRA_PIN, EXTRA_HIGH, EXTRA_IS_OUTPUT),
 *   EVENT_ERROR (extra EXTRA_MESSAGE),
 *   EVENT_STATUS (extra EXTRA_DATA = JSON target info),
 *   EVENT_GPIO_VALUE (extras EXTRA_PIN, EXTRA_VALUE)
 *
 * All operations reach the actual executing Xtensa firmware through the
 * native QEMU integration. Nothing is simulated. */
public class VirtualEsp32BridgeService extends Service {
    public static final String ACTION_START_GUEST = "dev.virtualesp32.action.START_GUEST";
    public static final String ACTION_STOP_GUEST = "dev.virtualesp32.action.STOP_GUEST";
    public static final String ACTION_RESET_GUEST = "dev.virtualesp32.action.RESET_GUEST";
    public static final String ACTION_SEND_UART = "dev.virtualesp32.action.SEND_UART";
    public static final String ACTION_WRITE_GPIO = "dev.virtualesp32.action.WRITE_GPIO";
    public static final String ACTION_QUERY_STATUS = "dev.virtualesp32.action.QUERY_STATUS";
    public static final String ACTION_READ_GPIO = "dev.virtualesp32.action.READ_GPIO";

    public static final String EVENT_GUEST_STARTED = "dev.virtualesp32.event.GUEST_STARTED";
    public static final String EVENT_GUEST_STOPPED = "dev.virtualesp32.event.GUEST_STOPPED";
    public static final String EVENT_GUEST_RESET = "dev.virtualesp32.event.GUEST_RESET";
    public static final String EVENT_UART_RX = "dev.virtualesp32.event.UART_RX";
    public static final String EVENT_GPIO_CHANGED = "dev.virtualesp32.event.GPIO_CHANGED";
    public static final String EVENT_ERROR = "dev.virtualesp32.event.ERROR";
    public static final String EVENT_STATUS = "dev.virtualesp32.event.STATUS";
    public static final String EVENT_GPIO_VALUE = "dev.virtualesp32.event.GPIO_VALUE";

    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_PIN = "pin";
    public static final String EXTRA_HIGH = "high";
    public static final String EXTRA_IS_OUTPUT = "isOutput";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_VALUE = "value";

    private final BridgeEventBus.Listener busListener = new BridgeEventBus.Listener() {
        @Override public void onGuestStarted() { sendEvent(EVENT_GUEST_STARTED, null); }
        @Override public void onGuestStopped() { sendEvent(EVENT_GUEST_STOPPED, null); }
        @Override public void onGuestReset() { sendEvent(EVENT_GUEST_RESET, null); }
        @Override public void onUartRx(String data) {
            Intent i = new Intent(EVENT_UART_RX).putExtra(EXTRA_DATA, data);
            sendEvent(i);
        }
        @Override public void onGpioChanged(int pin, boolean high, boolean isOutput) {
            Intent i = new Intent(EVENT_GPIO_CHANGED)
                .putExtra(EXTRA_PIN, pin)
                .putExtra(EXTRA_HIGH, high)
                .putExtra(EXTRA_IS_OUTPUT, isOutput);
            sendEvent(i);
        }
        @Override public void onError(String message) {
            Intent i = new Intent(EVENT_ERROR).putExtra(EXTRA_MESSAGE, message);
            sendEvent(i);
        }
    };

    private void sendEvent(String action, Intent base) {
        Intent i = base != null ? base : new Intent(action);
        if (base == null) i.setAction(action);
        i.setPackage("com.ghosttap");
        sendBroadcast(i);
    }

    private void sendEvent(Intent i) {
        i.setPackage("com.ghosttap");
        sendBroadcast(i);
    }

    private String buildTargetInfo() {
        try {
            JSONObject j = new JSONObject();
            // D3: reconciled status, same mapping as BridgeCommandReceiver QUERY_STATUS.
            String reconciled = FirmwareEmulatorRuntime.status();
            j.put("status", "running".equals(reconciled) ? "RUNNING" : "starting".equals(reconciled) ? "STARTING" : "error".equals(reconciled) ? "ERROR" : "STOPPED");
            j.put("cpu", "Xtensa LX6 / Classic ESP32");
            j.put("backend", "Native QEMU");
            j.put("firmware", FirmwareEmulatorRuntime.running() ? "running" : "starting".equals(reconciled) ? "starting" : "not loaded");
            JSONObject periph = new JSONObject();
            periph.put("uart", true);
            periph.put("gpio2_output", true);
            periph.put("gpio4_input", true);
            j.put("peripherals", periph);
            return j.toString();
        } catch (Exception e) {
            return "{\"status\":\"ERROR\"}";
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        BridgeEventBus.addListener(busListener);
    }

    @Override public void onDestroy() {
        BridgeEventBus.removeListener(busListener);
        super.onDestroy();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_NOT_STICKY;
        String action = intent.getAction();
        Context ctx = getApplicationContext();
        VirtualHal hal = VirtualHal.get(ctx);

        switch (action) {
            case ACTION_START_GUEST:
                try {
                    VirtualHal vhal = VirtualHal.get(ctx);
                    String path = vhal.get("firmwareImagePath", "");
                    if (path.isEmpty()) {
                        BridgeEventBus.postError("startGuest failed: no firmware imported (use Virtual ESP32 app to import a flash image first)");
                        break;
                    }
                    String result = FirmwareEmulatorRuntime.start(ctx, new java.io.File(path));
                    if (!"running".equals(result) || !FirmwareEmulatorRuntime.running()) {
                        BridgeEventBus.postError("startGuest failed: runtime did not reach running state (" + result + ")");
                    }
                } catch (Exception e) {
                    BridgeEventBus.postError("startGuest failed: " + e.getMessage());
                }
                break;
            case ACTION_STOP_GUEST:
                FirmwareEmulatorRuntime.stop();
                break;
            // D4: SOFT reset only (same QEMU session) — NOT the receiver's RESET_GUEST
            // full stop+start restart. GUEST_RESET is posted only on success; a failed
            // reset (guest not running) posts an error instead of a false reset event.
            case ACTION_RESET_GUEST:
                String resetResult = FirmwareEmulatorRuntime.reset();
                if ("ok".equals(resetResult)) {
                    BridgeEventBus.postGuestReset();
                } else {
                    BridgeEventBus.postError(resetResult);
                }
                break;
            case ACTION_SEND_UART: {
                String data = intent.getStringExtra(EXTRA_DATA);
                if (data == null) break;
                String tx = data.endsWith("\n") ? data : data + "\n";
                boolean ok = FirmwareEmulatorRuntime.running() && FirmwareEmulatorRuntime.writeUart(tx);
                if (ok) {
                    hal.log("BRIDGE", "UART TX via IPC: " + data.trim());
                } else {
                    hal.log("BRIDGE", "UART TX via IPC failed: no guest running");
                    BridgeEventBus.postError("UART send failed: guest not running");
                }
                break;
            }
            // D7: error surface mirrors BridgeCommandReceiver WRITE_GPIO exactly:
            // explicit errors for "guest not running", per-pin failure, and unsupported pins.
            case ACTION_WRITE_GPIO: {
                int pin = intent.getIntExtra(EXTRA_PIN, -1);
                boolean high = intent.getBooleanExtra(EXTRA_HIGH, false);
                if (!FirmwareEmulatorRuntime.running()) {
                    BridgeEventBus.postError("WRITE_GPIO failed: guest is not running");
                    break;
                }
                if (pin != 4) {
                    BridgeEventBus.postError("WRITE_GPIO failed: GPIO " + pin + " is not supported by this bridge");
                    break;
                }
                try {
                    ClassicEsp32QemuBackend.nativeSetGpio4(high ? 1 : 0);
                    hal.log("BRIDGE", "HOST GPIO4 -> " + (high ? "HIGH" : "LOW") + " (via IPC)");
                    BridgeEventBus.postGpioChanged(4, high, false);
                } catch (Throwable t) {
                    BridgeEventBus.postError("WRITE_GPIO pin 4 failed: " + t.getMessage());
                }
                break;
            }
            case ACTION_QUERY_STATUS: {
                Intent i = new Intent(EVENT_STATUS).putExtra(EXTRA_DATA, buildTargetInfo());
                sendEvent(i);
                break;
            }
            // D7: error surface mirrors BridgeCommandReceiver READ_GPIO exactly: explicit
            // errors for "guest not running", per-pin failure, and unsupported pins — never
            // a GPIO_VALUE event with value -1 standing in for an error.
            case ACTION_READ_GPIO: {
                int pin = intent.getIntExtra(EXTRA_PIN, -1);
                if (!FirmwareEmulatorRuntime.running()) {
                    BridgeEventBus.postError("READ_GPIO failed: guest is not running");
                    break;
                }
                if (pin != 2) {
                    BridgeEventBus.postError("READ_GPIO failed: GPIO " + pin + " is not supported by this bridge");
                    break;
                }
                int value;
                try { value = ClassicEsp32QemuBackend.nativeGetGpio2(); }
                catch (Throwable t) {
                    BridgeEventBus.postError("READ_GPIO pin 2 failed: " + t.getMessage());
                    break;
                }
                Intent i = new Intent(EVENT_GPIO_VALUE)
                    .putExtra(EXTRA_PIN, pin)
                    .putExtra(EXTRA_VALUE, value);
                sendEvent(i);
                break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null; // Commands via startService, not binding
    }
}
