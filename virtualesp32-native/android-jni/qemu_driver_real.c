/* Real QEMU driver for libesp32_qemu_android.
 *
 * Compiled only when QEMU_CORE_AVAILABLE is defined and the Espressif QEMU
 * softmmu objects are linked in. Drives the classic ESP32 machine
 * (MACHINE_TYPE_NAME("esp32")) from Espressif's QEMU fork (QEMU 9.2 base).
 *
 * Verified against espressif/qemu @ esp-develop:
 * - qemu_init / qemu_main_loop / qemu_cleanup : include/sysemu/sysemu.h
 * - qemu_system_reset_request / qemu_system_shutdown_request :
 *   include/sysemu/runstate.h (ShutdownCause from qapi/run-state.json)
 * - Machine type "esp32" : hw/xtensa/esp32.c (TYPE_ESP32_MACHINE)
 * - UART0 <- serial_hd(0)            : hw/xtensa/esp32.c esp32_machine_init
 * - Flash  <- first IF_MTD drive      : drive_get(IF_MTD, 0, 0)
 * - eFuse  <- "drive" property on nvram.esp32.efuse (wired via -global)
 *
 * Guest serial: QEMU is the TCP *client* (-serial tcp:127.0.0.1:PORT) because
 * the Java EmulatorUartBridge is the listening server.
 *
 * Flash persistence: the image file is opened read-write by QEMU's MTD block
 * backend, so guest SPI-flash writes persist directly into the file the user
 * imported. No copy-on-write overlay is used.
 *
 * What this driver does NOT do (honest boundaries):
 * - Wi-Fi / Bluetooth: the classic ESP32 QEMU machine does not model the
 *   ESP32 radio. Guest Wi-Fi/BT firmware will not find those peripherals.
 *   The Android-hosted BLE GATT provisioning server stays separate.
 * - GPIO matrix/IOMUX: not modeled by this QEMU machine; no guest-to-Android
 *   GPIO mapping is claimed.
 * - qemu_init() calls exit() on fatal configuration errors. There is no
 *   recovery hook in QEMU 9.x; a fatal init error kills the app process.
 *   Arguments are validated in the JNI layer first to shrink this window.
 */
#ifdef QEMU_CORE_AVAILABLE

#include "qemu/osdep.h"
#include <fcntl.h>
#include <unistd.h>
#include "qemu_session.h"
#include <setjmp.h>

/* Exit interceptor defined in esp32_qemu_jni.c */
extern jmp_buf qemu_exit_jmp;
extern volatile int qemu_exit_code;
extern volatile int qemu_exit_active;

/* Driver API prototypes (required by -Wmissing-prototypes) */
int qemu_driver_start(qemu_session_t *s);
void qemu_driver_request_reset(qemu_session_t *s);
void qemu_driver_request_stop(qemu_session_t *s);
void qemu_driver_join(qemu_session_t *s);


#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>

#include "sysemu/sysemu.h"
#include "sysemu/runstate.h"
#include "qapi/qapi-types-run-state.h"

#define LOG_TAG "esp32_qemu_driver"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void driver_set_error(qemu_session_t *s, const char *msg) {
    pthread_mutex_lock(&s->lock);
    strncpy(s->error_msg, msg, sizeof(s->error_msg) - 1);
    s->error_msg[sizeof(s->error_msg) - 1] = '\0';
    s->state = QEMU_STATE_ERROR;
    pthread_mutex_unlock(&s->lock);
}

/* QEMU runs here. qemu_init() + qemu_main_loop() + qemu_cleanup() mirror
 * what qemu-system-xtensa's main() does (system/main.c). */
static void *qemu_worker_thread(void *arg) {
    qemu_session_t *s = (qemu_session_t *)arg;

    char flash_drive[PATH_MAX_LOCAL + 64];
    char efuse_drive[PATH_MAX_LOCAL + 64];
    char serial_arg[64];
    snprintf(flash_drive, sizeof(flash_drive), "file=%s,if=mtd,format=raw", s->flash_path);
    snprintf(serial_arg, sizeof(serial_arg), "tcp:127.0.0.1:%d", s->uart_port);

    int use_efuse = s->efuse_path[0] != '\0';
    if (use_efuse) {
        snprintf(efuse_drive, sizeof(efuse_drive), "file=%s,if=none,id=efusedrv,format=raw",
                 s->efuse_path);
    }

    /* Build argv for qemu_init(int argc, char **argv).
     * String literals are const; qemu_init does not modify argv. */
    const char *argv[24];
    int argc = 0;
    argv[argc++] = "qemu-system-xtensa";
    argv[argc++] = "-machine"; argv[argc++] = "esp32";
    argv[argc++] = "-drive";   argv[argc++] = flash_drive;
    if (use_efuse) {
        argv[argc++] = "-drive"; argv[argc++] = efuse_drive;
        argv[argc++] = "-global"; argv[argc++] = "nvram.esp32.efuse.drive=efusedrv";
    }
    argv[argc++] = "-serial";  argv[argc++] = serial_arg;
    argv[argc++] = "-monitor"; argv[argc++] = "none";
    argv[argc++] = "-net"; argv[argc++] = "none";
    if (s->rom_path[0] != '\0') {
        argv[argc++] = "-L"; argv[argc++] = s->rom_path;
        LOGI("qemu_init: QEMU data dir (ROMs) = %s", s->rom_path);
    }
    argv[argc++] = "-nographic";
    argv[argc] = NULL;

    LOGI("qemu_init: machine=esp32 flash=%s serial=%s efuse=%s",
         s->flash_path, serial_arg, use_efuse ? s->efuse_path : "(none)");

    /* Capture QEMU's stderr to see the actual error message */
    int stderr_pipe[2];
    int saved_stderr = -1;
    char qemu_stderr[4096] = {0};
    if (pipe(stderr_pipe) == 0) {
        saved_stderr = dup(STDERR_FILENO);
        dup2(stderr_pipe[1], STDERR_FILENO);
        close(stderr_pipe[1]);
        /* Make read end non-blocking */
        int flags = fcntl(stderr_pipe[0], F_GETFL, 0);
        fcntl(stderr_pipe[0], F_SETFL, flags | O_NONBLOCK);
    }

    /* qemu_init() calls exit() on fatal errors - intercepted via longjmp */
    qemu_exit_active = 1;
    if (setjmp(qemu_exit_jmp)) {
        qemu_exit_active = 0;
        /* Restore stderr and capture QEMU's error output */
        if (saved_stderr >= 0) {
            fflush(stderr);
            dup2(saved_stderr, STDERR_FILENO);
            close(saved_stderr);
            ssize_t n = read(stderr_pipe[0], qemu_stderr, sizeof(qemu_stderr)-1);
            close(stderr_pipe[0]);
            if (n > 0) {
                qemu_stderr[n] = '\0';
                /* Trim to last 500 chars for readability */
                char *err_start = qemu_stderr;
                size_t len = strlen(qemu_stderr);
                if (len > 500) err_start = qemu_stderr + len - 500;
                LOGE("QEMU stderr: %s", err_start);
            }
        }
        char msg[1024];
        if (qemu_stderr[0]) {
            snprintf(msg, sizeof(msg), "QEMU init failed (exit %d): %.500s", qemu_exit_code, qemu_stderr);
        } else {
            snprintf(msg, sizeof(msg), "QEMU init failed (exit %d)", qemu_exit_code);
        }
        driver_set_error(s, msg);
        pthread_mutex_lock(&s->lock);
        s->state = QEMU_STATE_ERROR;
        pthread_mutex_unlock(&s->lock);
        return NULL;
    }
    qemu_init(argc, (char **)argv);
    qemu_exit_active = 0;

    pthread_mutex_lock(&s->lock);
    if (s->state == QEMU_STATE_STARTING) s->state = QEMU_STATE_RUNNING;
    pthread_mutex_unlock(&s->lock);
    LOGI("QEMU main loop starting — Xtensa LX6 guest is executing");

    int status = qemu_main_loop();
    qemu_cleanup(status);

    pthread_mutex_lock(&s->lock);
    if (s->state != QEMU_STATE_ERROR) s->state = QEMU_STATE_STOPPED;
    pthread_mutex_unlock(&s->lock);
    LOGI("QEMU main loop exited (status=%d)", status);
    return NULL;
}

int qemu_driver_start(qemu_session_t *s) {
    if (pthread_create(&s->thread, NULL, qemu_worker_thread, s) != 0) {
        driver_set_error(s, "failed to create QEMU worker thread");
        return -1;
    }
    /* Wait briefly for the worker to report running or error. qemu_init is
     * synchronous inside the worker, so a quick spin tells us whether init
     * passed. exit()-on-error inside qemu_init would kill the process here
     * instead of returning — documented in the file header. */
    for (int i = 0; i < 100; i++) {
        pthread_mutex_lock(&s->lock);
        qemu_state_t st = s->state;
        pthread_mutex_unlock(&s->lock);
        if (st == QEMU_STATE_RUNNING) return 0;
        if (st == QEMU_STATE_ERROR) return -1;
        usleep(50 * 1000);
    }
    /* Still starting after 5s: qemu_init may be retrying the UART TCP
     * connect. Leave it running; the Java layer polls status(). */
    return 0;
}

void qemu_driver_request_reset(qemu_session_t *s) {
    qemu_system_reset_request(SHUTDOWN_CAUSE_HOST_QMP_SYSTEM_RESET);
    LOGI("system reset requested");
}

void qemu_driver_request_stop(qemu_session_t *s) {
    pthread_mutex_lock(&s->lock);
    if (s->state == QEMU_STATE_RUNNING || s->state == QEMU_STATE_STARTING) {
        s->state = QEMU_STATE_STOPPING;
    }
    pthread_mutex_unlock(&s->lock);
    qemu_system_shutdown_request(SHUTDOWN_CAUSE_HOST_QMP_QUIT);
    LOGI("system shutdown requested");
}

void qemu_driver_join(qemu_session_t *s) {
    pthread_join(s->thread, NULL);
    LOGI("QEMU worker thread joined");
}

#endif /* QEMU_CORE_AVAILABLE */
