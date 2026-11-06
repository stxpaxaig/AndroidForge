/* libesp32_qemu_android — JNI lifecycle for the classic ESP32 QEMU backend.
 *
 * Java contract (dev.virtualesp32.ClassicEsp32QemuBackend):
 *   nativeStart(flashImage, efuseImage, uartPort) -> long handle (0 = failure)
 *   nativeReset(handle)
 *   nativeStop(handle)
 *   nativeGetLastError() -> String with the precise failure reason
 *
 * Design:
 * - QEMU's softmmu (qemu_init / qemu_main_loop) runs on a dedicated worker
 *   thread. Only one session may exist per process (QEMU keeps global state);
 *   the Java layer already enforces this.
 * - The guest's UART0 is wired as a TCP *client* (-serial tcp:127.0.0.1:PORT)
 *   because EmulatorUartBridge on the Java side is the listening server.
 * - Flash persistence: the merged image file is opened read-write by QEMU's
 *   MTD block backend, so guest flash writes land directly in the file.
 * - This file NEVER fakes execution. If the QEMU core was not compiled in
 *   (QEMU_CORE_AVAILABLE undefined), nativeStart returns 0 and
 *   nativeGetLastError explains exactly what is missing.
 *
 * QEMU-as-a-library hazards (documented, not hidden):
 * - qemu_init() calls exit() on fatal argument/config errors. A bad machine
 *   configuration can therefore kill the app process instead of returning an
 *   error. Arguments are validated here before the worker starts, but a
 *   QEMU-internal fatal error remains a process kill. This matches how every
 *   QEMU embedding behaves; there is no clean recovery hook in QEMU 9.x.
 * - qemu_system_shutdown_request() is async: it asks the main loop to exit.
 *   nativeStop joins the worker thread so resources are really freed.
 */
#include <jni.h>
#include <setjmp.h>
#include <stdlib.h>
#include <unistd.h>

/* Exit interceptor: QEMU calls exit() on fatal errors, which would kill the
 * Android app. We intercept it with longjmp to return gracefully instead. */
jmp_buf qemu_exit_jmp;
volatile int qemu_exit_code = 0;
volatile int qemu_exit_active = 0;

void exit(int status) {
    if (qemu_exit_active) {
        qemu_exit_code = status;
        longjmp(qemu_exit_jmp, 1);
    }
    /* If not in QEMU init, use real exit */
    _exit(status);
}
#include <pthread.h>
#include <stdlib.h>
#include <unistd.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/stat.h>
#include <android/log.h>

#include "qemu/osdep.h"
#include "qemu_session.h"
/* JNI prototypes (required by QEMU's -Wmissing-prototypes -Werror) */
JNIEXPORT jlong JNICALL
Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeStart(JNIEnv *env, jclass clazz,
                                                         jstring flashImage,
                                                         jstring efuseImage,
                                                         jstring romDir,
                                                         jint uartPort);
JNIEXPORT void JNICALL
Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeReset(JNIEnv *env, jclass clazz,
                                                         jlong handle);
JNIEXPORT void JNICALL
Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeStop(JNIEnv *env, jclass clazz,
                                                        jlong handle);
JNIEXPORT jstring JNICALL
Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeGetLastError(JNIEnv *env, jclass clazz);
JNIEXPORT jint JNICALL
Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeGetGpio2(JNIEnv *env, jclass clazz);
JNIEXPORT void JNICALL
Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeSetGpio4(JNIEnv *env, jclass clazz, jint level);



#define LOG_TAG "esp32_qemu_android"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ------------------------------------------------------------------ */
/* Driver: real QEMU core vs. stub                                      */
/* ------------------------------------------------------------------ */
#ifdef QEMU_CORE_AVAILABLE
/* Real driver lives in qemu_driver_real.c and is linked against the
 * Espressif QEMU softmmu objects. Declarations: */
extern int qemu_driver_start(qemu_session_t *s);
extern void qemu_driver_request_reset(qemu_session_t *s);
extern void qemu_driver_request_stop(qemu_session_t *s);
extern void qemu_driver_join(qemu_session_t *s);
#else
/* Stub driver: precise errors, never fake success. Lives below. */
static int qemu_driver_start(qemu_session_t *s) {
    (void)s;
    return -1;
}
static void qemu_driver_request_reset(qemu_session_t *s) { (void)s; }
static void qemu_driver_request_stop(qemu_session_t *s) { (void)s; }
static void qemu_driver_join(qemu_session_t *s) { (void)s; }
static const char *qemu_driver_missing_reason(void) {
    return "QEMU core not compiled into this APK (QEMU_CORE_AVAILABLE undefined). "
           "The Espressif QEMU fork was not cross-compiled for Android/Bionic when "
           "this library was built. See app/src/main/cpp/qemu/android/BUILD.md for "
           "the port procedure and the documented blocker boundary.";
}
#endif

/* ------------------------------------------------------------------ */
/* JNI entry points                                                     */
/* ------------------------------------------------------------------ */

static char g_last_error[1024] = {0};
static pthread_mutex_t g_error_lock = PTHREAD_MUTEX_INITIALIZER;

static void set_last_error(const char *msg) {
    pthread_mutex_lock(&g_error_lock);
    strncpy(g_last_error, msg, sizeof(g_last_error) - 1);
    g_last_error[sizeof(g_last_error) - 1] = '\0';
    pthread_mutex_unlock(&g_error_lock);
}

static qemu_session_t *handle_to_session(jlong handle) {
    if (handle == 0) return NULL;
    return (qemu_session_t *)(intptr_t)handle;
}

/* Validate arguments before touching QEMU. Returns 0 on success, or a
 * static error string on failure. */
static const char *validate_start_args(const char *flash, const char *efuse, int port) {
    static char err[512];
    struct stat st;
    if (!flash || !flash[0]) return "flash image path is empty";
    if (stat(flash, &st) != 0) {
        snprintf(err, sizeof(err), "flash image not accessible: %s (%s)", flash, strerror(errno));
        return err;
    }
    if (st.st_size != (2L<<20) && st.st_size != (4L<<20) &&
        st.st_size != (8L<<20) && st.st_size != (16L<<20)) {
        snprintf(err, sizeof(err), "flash image must be 2/4/8/16 MiB, got %lld bytes",
                 (long long)st.st_size);
        return err;
    }
    if (access(flash, R_OK | W_OK) != 0) {
        snprintf(err, sizeof(err), "flash image needs read-write access for guest persistence: %s",
                 strerror(errno));
        return err;
    }
    if (efuse && efuse[0] && stat(efuse, &st) == 0 && st.st_size != 124) {
        snprintf(err, sizeof(err), "eFuse backing file must be 124 bytes, got %lld",
                 (long long)st.st_size);
        return err;
    }
    if (port <= 0 || port > 65535) return "UART loopback port out of range";
    return NULL;
}

JNIEXPORT jlong JNICALL
Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeStart(JNIEnv *env, jclass clazz,
                                                         jstring flashImage,
                                                         jstring efuseImage,
                                                         jstring romDir,
                                                         jint uartPort) {
    (void)clazz;
    const char *flash = (*env)->GetStringUTFChars(env, flashImage, NULL);
    const char *efuse = efuseImage ? (*env)->GetStringUTFChars(env, efuseImage, NULL) : "";
    const char *romdir = romDir ? (*env)->GetStringUTFChars(env, romDir, NULL) : "";

    const char *bad = validate_start_args(flash, efuse, uartPort);
    if (bad) {
        set_last_error(bad);
        LOGE("nativeStart rejected: %s", bad);
        (*env)->ReleaseStringUTFChars(env, flashImage, flash);
        if (efuseImage) (*env)->ReleaseStringUTFChars(env, efuseImage, efuse);
        return 0;
    }

#ifndef QEMU_CORE_AVAILABLE
    set_last_error(qemu_driver_missing_reason());
    LOGE("nativeStart: %s", qemu_driver_missing_reason());
    (*env)->ReleaseStringUTFChars(env, flashImage, flash);
    if (efuseImage) (*env)->ReleaseStringUTFChars(env, efuseImage, efuse);
    if (romDir) (*env)->ReleaseStringUTFChars(env, romDir, romdir);
    return 0; /* 0 = failure: Java reports backend unavailable. Never fake it. */
#endif

    qemu_session_t *s = calloc(1, sizeof(*s));
    if (!s) {
        set_last_error("out of memory allocating QEMU session");
        (*env)->ReleaseStringUTFChars(env, flashImage, flash);
        if (efuseImage) (*env)->ReleaseStringUTFChars(env, efuseImage, efuse);
        return 0;
    }
    pthread_mutex_init(&s->lock, NULL);
    s->state = QEMU_STATE_STARTING;
    strncpy(s->flash_path, flash, sizeof(s->flash_path) - 1);
    if (efuse) strncpy(s->efuse_path, efuse, sizeof(s->efuse_path) - 1);
    if (romdir) strncpy(s->rom_path, romdir, sizeof(s->rom_path) - 1);
    s->uart_port = uartPort;

    (*env)->ReleaseStringUTFChars(env, flashImage, flash);
    if (efuseImage) (*env)->ReleaseStringUTFChars(env, efuseImage, efuse);
    if (romDir) (*env)->ReleaseStringUTFChars(env, romDir, romdir);

#ifdef QEMU_CORE_AVAILABLE
    if (qemu_driver_start(s) != 0) {
        char tmp[1024];
        pthread_mutex_lock(&s->lock);
        strncpy(tmp, s->error_msg, sizeof(tmp) - 1);
        pthread_mutex_unlock(&s->lock);
        set_last_error(tmp[0] ? tmp : "QEMU driver failed to start the machine");
        pthread_mutex_destroy(&s->lock);
        free(s);
        return 0;
    }
#endif

    LOGI("nativeStart: session %p starting (flash=%s uartPort=%d)",
         (void *)s, s->flash_path, s->uart_port);
    return (jlong)(intptr_t)s;
}

JNIEXPORT void JNICALL
Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeReset(JNIEnv *env, jclass clazz,
                                                         jlong handle) {
    (void)env; (void)clazz;
    qemu_session_t *s = handle_to_session(handle);
    if (!s) return;
    LOGI("nativeReset: session %p", (void *)s);
#ifdef QEMU_CORE_AVAILABLE
    qemu_driver_request_reset(s);
#endif
}

JNIEXPORT void JNICALL
Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeStop(JNIEnv *env, jclass clazz,
                                                        jlong handle) {
    (void)env; (void)clazz;
    qemu_session_t *s = handle_to_session(handle);
    if (!s) return;
    LOGI("nativeStop: session %p", (void *)s);
#ifdef QEMU_CORE_AVAILABLE
    qemu_driver_request_stop(s);
    qemu_driver_join(s);
#endif
    pthread_mutex_destroy(&s->lock);
    free(s);
}

JNIEXPORT jstring JNICALL
Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeGetLastError(JNIEnv *env, jclass clazz) {
    (void)clazz;
    char tmp[1024];
    pthread_mutex_lock(&g_error_lock);
    strncpy(tmp, g_last_error, sizeof(tmp) - 1);
    tmp[sizeof(tmp) - 1] = '\0';
    pthread_mutex_unlock(&g_error_lock);
    return (*env)->NewStringUTF(env, tmp);
}

/* Reads the live emulated GPIO2 output level from QEMU's ESP32 GPIO model.
 * Returns 0 (LOW), 1 (HIGH), or -1 (unavailable / not configured as output).
 * This reads actual guest-driven peripheral state, not a Java-side model. */
extern int esp32_gpio_get_output_level(int pin);
/* Injects an input level into the emulated GPIO via QEMU's qdev input mechanism. */
extern void esp32_gpio_inject_input(int pin, int level);

JNIEXPORT jint JNICALL
Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeGetGpio2(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
#ifdef QEMU_CORE_AVAILABLE
    return (jint)esp32_gpio_get_output_level(2);
#else
    return (jint)-1;
#endif
}

JNIEXPORT void JNICALL
Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeSetGpio4(JNIEnv *env, jclass clazz, jint level) {
    (void)env; (void)clazz;
#ifdef QEMU_CORE_AVAILABLE
    esp32_gpio_inject_input(4, level ? 1 : 0);
#endif
}
