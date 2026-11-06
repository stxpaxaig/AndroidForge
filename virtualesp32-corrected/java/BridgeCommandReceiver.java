package dev.virtualesp32;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONObject;
import java.io.File;

/** Handles Ghost Tap IPC commands directly from explicit broadcasts. */
public class BridgeCommandReceiver extends BroadcastReceiver {
    private static boolean busListenerRegistered = false;

    /** Registers the bus listener that re-broadcasts guest events (UART_RX, GPIO_CHANGED,
     * ERROR) to Ghost Tap. Idempotent: registering twice is harmless. Called once at app
     * start from MainActivity.onCreate (D5) so Ghost Tap gets UART events for UI-started
     * guests too, and kept on the receiver START/RESET paths so a process woken by
     * broadcast alone still registers it. Public so MainActivity can call it. */
    public static synchronized void ensureBusListener(final Context context) {
        if (busListenerRegistered) return;
        busListenerRegistered = true;
        BridgeEventBus.addListener(new BridgeEventBus.Listener() {
            @Override public void onGuestStarted() {}
            @Override public void onGuestStopped() {}
            @Override public void onGuestReset() {}
            @Override public void onUartRx(String data) {
                Intent i = new Intent("dev.virtualesp32.event.UART_RX");
                i.putExtra("data", data);
                i.setPackage("com.ghosttap");
                context.sendBroadcast(i);
            }
            @Override public void onGpioChanged(int pin, boolean high, boolean isOutput) {
                Intent i = new Intent("dev.virtualesp32.event.GPIO_CHANGED");
                i.putExtra("pin", pin);
                i.putExtra("high", high);
                i.putExtra("isOutput", isOutput);
                i.setPackage("com.ghosttap");
                context.sendBroadcast(i);
            }
            @Override public void onError(String message) {
                Intent i = new Intent("dev.virtualesp32.event.ERROR");
                i.putExtra("message", message);
                i.setPackage("com.ghosttap");
                context.sendBroadcast(i);
            }
        });
    }

    private void log(Context context, String msg) {
        try (java.io.FileWriter w = new java.io.FileWriter(
                new File(context.getFilesDir(), "bridge_receiver.log"), true)) {
            w.write(System.currentTimeMillis() + " " + msg + "\n");
        } catch (Exception ignored) {}
    }

    private void sendEvent(Context context, Intent event) {
        try {
            event.setPackage("com.ghosttap");
            context.sendBroadcast(event);
        } catch (Exception ignored) {}
    }

    private void sendError(Context context, String message) {
        Intent i = new Intent("dev.virtualesp32.event.ERROR");
        i.putExtra("message", message);
        sendEvent(context, i);
    }

    @Override public void onReceive(Context context, Intent intent) {
        log(context, "RECEIVER FIRED");
        if (intent == null || intent.getAction() == null) {
            log(context, "NULL INTENT/ACTION");
            return;
        }
        final String action = intent.getAction();
        log(context, "ACTION=" + action);
        try {
            switch (action) {
                case "dev.virtualesp32.action.LOAD_FIRMWARE": {
                    Uri selectedUri = intent.getData();
                    if (selectedUri == null) {
                        String uriExtra = intent.getStringExtra("uri");
                        if (uriExtra != null && !uriExtra.trim().isEmpty()) {
                            selectedUri = Uri.parse(uriExtra);
                        }
                    }
                    // Backward compatibility with older controllers that sent a file path.
                    if (selectedUri == null) {
                        String legacyPath = intent.getStringExtra("path");
                        if (legacyPath != null && !legacyPath.trim().isEmpty()) {
                            selectedUri = Uri.fromFile(new File(legacyPath));
                        }
                    }
                    if (selectedUri == null) {
                        sendError(context, "LOAD_FIRMWARE failed: no content URI or legacy path supplied");
                        log(context, "LOAD_FIRMWARE FAILED: missing URI/path");
                        break;
                    }
                    FirmwareImageManager.Inspection imported =
                            FirmwareImageManager.importAndInspect(context, selectedUri);
                    if (!imported.valid || imported.image == null) {
                        sendError(context, "LOAD_FIRMWARE rejected: " + imported.summary);
                        log(context, "LOAD_FIRMWARE REJECTED: " + imported.summary);
                        break;
                    }
                    VirtualHal hal = VirtualHal.get(context);
                    hal.put("firmwareImagePath", imported.image.getAbsolutePath());
                    hal.put("firmwareImageSummary", imported.summary);
                    hal.log("EMU", imported.summary + "; imported only, not executed");

                    Intent loaded = new Intent("dev.virtualesp32.event.FIRMWARE_LOADED");
                    loaded.putExtra("path", imported.image.getAbsolutePath());
                    loaded.putExtra("filename", imported.image.getName());
                    loaded.putExtra("size", imported.image.length());
                    loaded.putExtra("imageType", "ESP-IDF merged ESP32 flash image");
                    loaded.putExtra("validationSummary", imported.summary);
                    loaded.putExtra("summary", imported.summary);
                    sendEvent(context, loaded);
                    log(context, "LOAD_FIRMWARE OK: " + imported.image.length() + " bytes");
                    break;
                }
                case "dev.virtualesp32.action.START_GUEST": {
                    VirtualHal hal = VirtualHal.get(context);
                    String path = hal.get("firmwareImagePath", "");
                    if (path.isEmpty()) {
                        sendError(context, "START failed: no firmware imported in VirtualESP32 app");
                        log(context, "START FAILED: no firmware path");
                        break;
                    }
                    ensureBusListener(context.getApplicationContext());
                    String result = FirmwareEmulatorRuntime.start(context, new File(path));
                    if (!"running".equals(result) || !FirmwareEmulatorRuntime.running()) {
                        sendError(context, "START failed: runtime did not reach running state (" + result + ")");
                        log(context, "START FAILED: runtime state=" + FirmwareEmulatorRuntime.status() + " result=" + result);
                        break;
                    }
                    sendEvent(context, new Intent("dev.virtualesp32.event.GUEST_STARTED"));
                    log(context, "START OK: runtime confirmed running");
                    break;
                }
                case "dev.virtualesp32.action.STOP_GUEST": {
                    FirmwareEmulatorRuntime.stop();
                    sendEvent(context, new Intent("dev.virtualesp32.event.GUEST_STOPPED"));
                    log(context, "STOP OK");
                    break;
                }
                // RESET_GUEST is a FULL restart (D4): stop() tears the session down, then
                // start() builds a new one (flash re-inspected, UART re-created, GPIO
                // monitor restarted). That is NOT the same as FirmwareEmulatorRuntime.reset()
                // (soft reset, same session) used by the app UI and the bridge service.
                // The firmware path is checked BEFORE stop() so a restart that cannot
                // proceed never tears down a running guest. GUEST_RESET is sent only when
                // the restart actually reaches running.
                case "dev.virtualesp32.action.RESET_GUEST": {
                    VirtualHal hal = VirtualHal.get(context);
                    String path = hal.get("firmwareImagePath", "");
                    if (path.isEmpty()) {
                        sendError(context, "RESET failed: no firmware imported in VirtualESP32 app");
                        log(context, "RESET FAILED: no firmware path");
                        break;
                    }
                    FirmwareEmulatorRuntime.stop();
                    ensureBusListener(context.getApplicationContext());
                    String result = FirmwareEmulatorRuntime.start(context, new File(path));
                    if (!"running".equals(result) || !FirmwareEmulatorRuntime.running()) {
                        sendError(context, "RESET failed: runtime did not return to running state (" + result + ")");
                        log(context, "RESET FAILED: runtime state=" + FirmwareEmulatorRuntime.status() + " result=" + result);
                        break;
                    }
                    sendEvent(context, new Intent("dev.virtualesp32.event.GUEST_RESET"));
                    log(context, "RESET OK: runtime confirmed running");
                    break;
                }
                case "dev.virtualesp32.action.SEND_UART": {
                    String data = intent.getStringExtra("data");
                    if (data == null) data = "";
                    if (!FirmwareEmulatorRuntime.running() || !FirmwareEmulatorRuntime.writeUart(data + "\n")) {
                        sendError(context, "SEND_UART failed: guest UART is not connected");
                        log(context, "SEND_UART FAILED: guest not connected");
                    } else {
                        log(context, "SEND_UART OK: " + data);
                    }
                    break;
                }
                case "dev.virtualesp32.action.WRITE_GPIO": {
                    int pin = intent.getIntExtra("pin", -1);
                    boolean high = intent.getBooleanExtra("high", false);
                    if (!FirmwareEmulatorRuntime.running()) {
                        sendError(context, "WRITE_GPIO failed: guest is not running");
                    } else if (pin == 4) {
                        try {
                            ClassicEsp32QemuBackend.nativeSetGpio4(high ? 1 : 0);
                            Intent event = new Intent("dev.virtualesp32.event.GPIO_CHANGED");
                            event.putExtra("pin", 4);
                            event.putExtra("high", high);
                            event.putExtra("isOutput", false);
                            sendEvent(context, event);
                        } catch (Throwable t) {
                            sendError(context, "WRITE_GPIO pin 4 failed: " + t.getMessage());
                        }
                    } else {
                        sendError(context, "WRITE_GPIO failed: GPIO " + pin + " is not supported by this bridge");
                    }
                    log(context, "WRITE_GPIO pin=" + pin + " high=" + high);
                    break;
                }
                case "dev.virtualesp32.action.QUERY_STATUS": {
                    JSONObject j = new JSONObject();
                    // D3: status() reconciles the Java flag with the native QEMU session state,
                    // so this reports RUNNING only while QEMU is actually alive — never RUNNING
                    // after QEMU has exited. "starting" is reported while QEMU is still coming
                    // up; "error" when the session died under a "running" flag.
                    String reconciled = FirmwareEmulatorRuntime.status();
                    j.put("status", "running".equals(reconciled) ? "RUNNING" : "starting".equals(reconciled) ? "STARTING" : "error".equals(reconciled) ? "ERROR" : "STOPPED");
                    j.put("cpu", "Xtensa LX6 / Classic ESP32");
                    j.put("backend", "Native QEMU");
                    j.put("firmware", FirmwareEmulatorRuntime.running() ? "running" : "starting".equals(reconciled) ? "starting" : "not loaded");
                    Intent event = new Intent("dev.virtualesp32.event.STATUS");
                    event.putExtra("data", j.toString());
                    sendEvent(context, event);
                    log(context, "QUERY_STATUS sent");
                    break;
                }
                case "dev.virtualesp32.action.READ_GPIO": {
                    int pin = intent.getIntExtra("pin", -1);
                    if (!FirmwareEmulatorRuntime.running()) {
                        sendError(context, "READ_GPIO failed: guest is not running");
                    } else if (pin == 2) {
                        int value;
                        try { value = ClassicEsp32QemuBackend.nativeGetGpio2(); }
                        catch (Throwable t) {
                            sendError(context, "READ_GPIO pin 2 failed: " + t.getMessage());
                            break;
                        }
                        Intent event = new Intent("dev.virtualesp32.event.GPIO_VALUE");
                        event.putExtra("pin", 2);
                        event.putExtra("value", value);
                        sendEvent(context, event);
                    } else {
                        sendError(context, "READ_GPIO failed: GPIO " + pin + " is not supported by this bridge");
                    }
                    log(context, "READ_GPIO pin=" + pin);
                    break;
                }
                default:
                    sendError(context, "Unsupported bridge command: " + action);
                    log(context, "UNSUPPORTED ACTION=" + action);
            }
        } catch (Exception e) {
            log(context, "HANDLER EXCEPTION: " + e);
            sendError(context, "Command failed: " + e.getMessage());
        }
    }
}
