package dev.virtualesp32;

import android.content.Context;
import java.io.File;

/** Process-level lifecycle and HAL bridge for the optional native classic ESP32 QEMU backend.
 *
 * "Running" is reconciled against the native QEMU session state (D3). The Java flag alone
 * used to stay "running" forever after QEMU exited, so running() is now true only when the
 * flag says running AND the native session reports RUNNING (1) — except when the native
 * state cannot be probed at all (library not loaded, or a .so without
 * nativeGetSessionState), in which case the flag is the only signal; see the FALLBACK
 * comments in running() and status(). A guest CPU panic/reboot inside QEMU does NOT
 * change the native session state (QEMU keeps running), so "running" still cannot prove
 * guest health — only that the QEMU session is alive.
 *
 * status() strings: "stopped", "running", "starting" (native session exists but QEMU has
 * not reached RUNNING yet), "stopping", "error" (native session STOPPED/ERROR or gone
 * while the flag said running — latched once, with a single error event), "unavailable"
 * (no native library). QUERY_STATUS and the UI read status(), so they see the reconciled
 * state, not the raw flag.
 *
 * Resets (D4) — three different things share the word, do not mix them:
 *   reset() here is a SOFT reset: qemu_system_reset_request inside the SAME session
 *     (same flash, same UART bridge). It never restarts the session.
 *   BridgeCommandReceiver RESET_GUEST is a FULL restart: stop() + start() (new session,
 *     UART re-created, flash re-inspected).
 *   MainActivity "Power" and "Soft reset virtual runtime" do not touch the
 *     guest at all (BLE/virtual-HAL only); see MainActivity. */
final class FirmwareEmulatorRuntime {
    private static ClassicEsp32QemuBackend backend; private static EmulatorUartBridge uart; private static GuestGpioMonitor gpioMonitor; private static VirtualHal hal; private static Context appContext; private static String state="stopped";
    private FirmwareEmulatorRuntime(){}

    /** Reconciled state string (see class Javadoc). May latch "running"/"starting" to
     * "error" or promote "starting" to "running" when the native session state disagrees
     * with the flag; the latch fires its error event at most once per transition. */
    static synchronized String status(){return reconcileLocked();}

    /** True only when the Java flag says running AND the native session reports RUNNING (1).
     * D3 FALLBACK: when the native state cannot be probed — library not loaded, or the
     * loaded .so has no nativeGetSessionState symbol (the current prebuilt .so, until the
     * native side gains the symbol) — the flag is trusted alone, exactly as before this
     * fix. In that case the flag can still lie: a QEMU exit would keep reporting running.
     * In practice the flag can only become "running" via start(), which requires the
     * library, so the library-not-loaded branch is near-unreachable; the live fallback
     * case is the missing-symbol .so. A guest panic inside QEMU is NOT covered either:
     * native state stays RUNNING through a guest reboot, so running() stays true. */
    static synchronized boolean running(){
        if(!"running".equals(state))return false;
        int nativeState=ClassicEsp32QemuBackend.sessionState();
        if(!ClassicEsp32QemuBackend.sessionStateAvailable())return true; // D3 fallback (see Javadoc)
        return nativeState==1;
    }

    static synchronized String backendAvailability(){return ClassicEsp32QemuBackend.available()?"Native QEMU backend found":"Unavailable: "+ClassicEsp32QemuBackend.unavailableReason();}

    static synchronized String start(Context context,File flash)throws Exception {
        appContext=context.getApplicationContext();
        hal=VirtualHal.get(context);
        // Already-up path: re-inspect the flash file even when a session is running (D6).
        // The live session is NOT disturbed by this check: a bad file is logged and reported,
        // but the running guest keeps the image it already loaded.
        if("running".equals(state)||"starting".equals(state)){
            FirmwareImageManager.Inspection check=FirmwareImageManager.inspect(flash);
            if(!check.valid){
                String msg="Flash image on disk no longer validates: "+check.summary;
                if(hal!=null)hal.log("EMU",msg);
                BridgeEventBus.postError(msg);
            }
            int nativeState=ClassicEsp32QemuBackend.sessionState(); // probes; latches "symbol missing" if the .so lacks it
            if(!ClassicEsp32QemuBackend.sessionStateAvailable())return state; // D3 fallback: cannot probe, trust the flag
            if(nativeState==1){if("starting".equals(state))promoteStartingLocked();return "running";}
            if(nativeState==0)return "starting"; // session still coming up; do not start a second one
            if(nativeState==2)return "stopping"; // a stop is already in progress; do not start a second one
            // Native session is dead (STOPPED/ERROR/no session) but the flag says otherwise:
            // drop the stale session objects and fall through to a fresh start.
            if(hal!=null)hal.log("EMU","Stale session detected (native state="+nativeState+"); restarting guest");
            teardownStaleLocked();
            state="stopped";
        }
        if(!ClassicEsp32QemuBackend.available()){state="unavailable";VirtualHal.get(context).put("firmwareEmulatorState",state);return backendAvailability();}
        FirmwareImageManager.Inspection check=FirmwareImageManager.inspect(flash);if(!check.valid)throw new IllegalArgumentException(check.summary);
        teardownStaleLocked(); // never leak a previous UART bridge/backend into a new session
        uart=new EmulatorUartBridge(context);int port=uart.start();backend=new ClassicEsp32QemuBackend();
        File romDir=FirmwareImageManager.esp32RomDir(context);
        VirtualHal.get(context).log("EMU","ESP32 ROM dir ready: "+romDir.getAbsolutePath());
        try{
            backend.start(flash,FirmwareImageManager.efuseBackingFile(context),romDir,port);
            // D3: the native start waits up to ~5 s for RUNNING but returns success even if
            // still STARTING. Poll briefly so "running" is only claimed once the native
            // session really is RUNNING; otherwise report "starting" and let status() (or a
            // later START) promote it once QEMU is up. Polling blocks the calling thread
            // (main thread in both call sites) for at most ~1 s, on top of the native
            // start's own up-to-5 s wait.
            int nativeState=pollNativeStateLocked(1000);
            if(nativeState==1||!ClassicEsp32QemuBackend.sessionStateAvailable()){
                // RUNNING, or native state unprobable (old .so without the getter):
                // fall back to the flag, as before this fix.
                state="running";VirtualHal.get(context).put("firmwareEmulatorState",state);VirtualHal.get(context).log("EMU","Classic ESP32 QEMU session started");gpioMonitor=new GuestGpioMonitor(context);gpioMonitor.start();BridgeEventBus.postGuestStarted();return "running";
            }
            if(nativeState==0){
                state="starting";VirtualHal.get(context).put("firmwareEmulatorState",state);VirtualHal.get(context).log("EMU","Classic ESP32 QEMU session started but still STARTING; run status will promote it to running once QEMU is up");return "starting";
            }
            // Native session already STOPPED/ERROR (or gone) right after start: a failed start.
            throw new IllegalStateException("QEMU native session ended immediately after start (state="+nativeState+")");
        }catch(Exception e){teardownStaleLocked();state="error";VirtualHal.get(context).put("firmwareEmulatorState",state);BridgeEventBus.postError("Guest start failed: "+e.getMessage());throw e;}
    }

    /** Soft reset (D4/D6): asks QEMU to reset the guest CPU inside the SAME session
     * (qemu_system_reset_request). Same flash, same UART bridge, no revalidation, no
     * restart. A full restart is stop() + start() (BridgeCommandReceiver RESET_GUEST).
     * @return "ok" when the reset was requested; otherwise an error string, which is
     * also logged to the HAL log and posted as an error event, so callers that ignore
     * the return value still surface the failure. Never a silent no-op. */
    static synchronized String reset(){
        if(!running()){
            String msg="Guest reset failed: guest is not running";
            if(hal!=null)hal.log("EMU",msg);
            BridgeEventBus.postError(msg);
            return msg;
        }
        if(backend==null){ // unreachable when running() is true, but fail loudly rather than silently
            String msg="Guest reset failed: no QEMU backend";
            if(hal!=null)hal.log("EMU",msg);
            BridgeEventBus.postError(msg);
            return msg;
        }
        backend.reset();if(hal!=null)hal.log("EMU","QEMU system reset requested");return "ok";
    }

    static synchronized void stop(){teardownStaleLocked();state="stopped";if(hal!=null)hal.put("firmwareEmulatorState",state);BridgeEventBus.postGuestStopped();}
    static synchronized boolean writeUart(String value){return uart!=null&&uart.sendHostText(value);}

    /** Reconcile the Java flag with the native session state. Callers hold the class lock. */
    private static String reconcileLocked(){
        if("running".equals(state)){
            int nativeState=ClassicEsp32QemuBackend.sessionState();
            if(!ClassicEsp32QemuBackend.sessionStateAvailable())return state; // D3 fallback: cannot probe, flag only
            if(nativeState==1)return "running";
            if(nativeState==0)return "starting";
            if(nativeState==2)return "stopping";
            latchErrorLocked("QEMU session ended (native state="+nativeState+") while the app still reported running");
            return "error";
        }
        if("starting".equals(state)){
            int nativeState=ClassicEsp32QemuBackend.sessionState();
            if(!ClassicEsp32QemuBackend.sessionStateAvailable())return state; // D3 fallback: cannot probe, flag only
            if(nativeState==1){promoteStartingLocked();return "running";}
            if(nativeState==0)return "starting";
            if(nativeState==2)return "stopping";
            latchErrorLocked("QEMU session ended (native state="+nativeState+") while starting");
            return "error";
        }
        return state;
    }

    /** Promote "starting" to "running": start the GPIO monitor (not started by start()
     * while the session was still STARTING) and announce the guest. Callers hold the lock. */
    private static void promoteStartingLocked(){
        state="running";
        if(hal!=null){hal.put("firmwareEmulatorState",state);hal.log("EMU","QEMU session reached RUNNING");}
        if(gpioMonitor==null&&appContext!=null){gpioMonitor=new GuestGpioMonitor(appContext);gpioMonitor.start();}
        BridgeEventBus.postGuestStarted();
    }

    /** Latch the flag to "error" once (single log line + single error event). The stale
     * backend/uart/monitor objects are left in place so stop() can still clean them up;
     * start() tears them down before any fresh start. Callers hold the lock. */
    private static void latchErrorLocked(String why){
        if("error".equals(state))return;
        state="error";
        if(hal!=null){hal.put("firmwareEmulatorState",state);hal.log("EMU",why);}
        BridgeEventBus.postError(why);
    }

    /** Best-effort teardown of a previous (possibly stale) session's objects. Every step
     * is guarded so one broken native call cannot block the rest. Callers hold the lock. */
    private static void teardownStaleLocked(){
        if(gpioMonitor!=null){try{gpioMonitor.stop();}catch(Throwable ignored){}gpioMonitor=null;}
        if(uart!=null){try{uart.close();}catch(Throwable ignored){}uart=null;}
        if(backend!=null){try{backend.stop();}catch(Throwable ignored){}backend=null;}
    }

    /** Poll the native session state until it leaves STARTING (0) or the timeout elapses.
     * Returns the last observed state, or -1 when the state cannot be probed at all
     * (library not loaded, or .so without the getter — callers check
     * sessionStateAvailable() to tell that apart from "no session"). Callers hold the lock. */
    private static int pollNativeStateLocked(long timeoutMs){
        int nativeState=ClassicEsp32QemuBackend.sessionState();
        if(!ClassicEsp32QemuBackend.sessionStateAvailable())return -1;
        long deadline=System.currentTimeMillis()+timeoutMs;
        while(nativeState==0&&System.currentTimeMillis()<deadline){
            try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();return 0;}
            nativeState=ClassicEsp32QemuBackend.sessionState();
        }
        return nativeState;
    }
}
