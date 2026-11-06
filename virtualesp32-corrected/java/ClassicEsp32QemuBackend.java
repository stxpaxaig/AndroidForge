package dev.virtualesp32;

import java.io.File;

/** JNI contract for an Android-built Espressif QEMU classic-ESP32 core.
 * The QEMU core ships as a prebuilt native library (libesp32_qemu_android.so,
 * arm64-v8a, in jniLibs/); availability is false only when that library cannot
 * be loaded. The Java class never rebuilds the native code. */
final class ClassicEsp32QemuBackend {
    private static final boolean LOADED;
    private static final String LOAD_ERROR;
    /** Latched once a nativeGetSessionState call proves the loaded .so has no such symbol. */
    private static boolean sessionStateMissing;
    static {
        boolean loaded=false; String error="";
        try { System.loadLibrary("esp32_qemu_android"); loaded=true; }
        catch (UnsatisfiedLinkError e) { error="Native classic-ESP32 QEMU library is not packaged"; }
        LOADED=loaded; LOAD_ERROR=error;
    }
    private long handle;
    static boolean available(){return LOADED;}
    static String unavailableReason(){return LOADED?"":"Native classic-ESP32 QEMU library is not packaged; firmware import and validation work, execution does not.";}
    synchronized boolean start(File flashImage,File efuseImage,File romDir,int uartPort)throws Exception {
        if(!LOADED)throw new IllegalStateException(unavailableReason());
        if(handle!=0)throw new IllegalStateException("QEMU session is already running");
        handle=nativeStart(flashImage.getAbsolutePath(),efuseImage.getAbsolutePath(),romDir.getAbsolutePath(),uartPort);
        if(handle==0){
            String detail=nativeGetLastError();
            throw new IllegalStateException(detail==null||detail.isEmpty()
                ?"QEMU native backend failed to create a machine":detail);
        }
        return true;
    }
    synchronized void reset(){if(handle!=0)nativeReset(handle);}
    synchronized void stop(){if(handle!=0){nativeStop(handle);handle=0;}}
    private static native long nativeStart(String flashImage,String efuseImage,String romDir,int uartPort);
    private static native void nativeReset(long handle);
    private static native void nativeStop(long handle);
    /** Precise reason the last nativeStart returned 0. Empty when unknown. */
    private static native String nativeGetLastError();
    /** Native QEMU session state (qemu_state_t): 0=STARTING, 1=RUNNING, 2=STOPPING,
     * 3=STOPPED, 4=ERROR; -1 = no session exists. Implemented on the native side. */
    private static native int nativeGetSessionState();
    /** Current native session state (see nativeGetSessionState), or -1 when the native
     * library is not loaded. Also returns -1 when the loaded .so has no
     * nativeGetSessionState symbol yet (pre-update prebuilt .so): callers must check
     * sessionStateAvailable() to tell "no session" apart from "cannot probe".
     * Never throws. */
    static int sessionState(){
        if(!LOADED||sessionStateMissing)return -1;
        try{return nativeGetSessionState();}
        catch(UnsatisfiedLinkError e){sessionStateMissing=true;return -1;}
        catch(Throwable t){return -1;}
    }
    /** True only when the native session state can actually be probed: the library is
     * loaded AND its nativeGetSessionState symbol exists. False for the current
     * prebuilt .so until the native side gains that symbol; availability is learned
     * on the first sessionState() probe (before any probe it is optimistically true
     * whenever the library loaded, so callers must probe via sessionState() first). */
    static boolean sessionStateAvailable(){return LOADED&&!sessionStateMissing;}
    /** True only when a native session exists and reports RUNNING (1). Strict probe:
     * false whenever the state cannot be probed (library missing, symbol missing,
     * or no session). Callers that need the legacy flag fallback use
     * FirmwareEmulatorRuntime.running() instead. */
    static boolean qemuAlive(){return sessionState()==1;}
    /** Live emulated GPIO2 output level from QEMU's ESP32 GPIO model.
     * Returns 0 (LOW), 1 (HIGH), or -1 (unavailable / not an output). */
    static native int nativeGetGpio2();
    /** Injects an input level into emulated GPIO4 via QEMU's qdev input mechanism.
     * The executing Xtensa firmware reads this from the GPIO peripheral. */
    static native void nativeSetGpio4(int level);
}
