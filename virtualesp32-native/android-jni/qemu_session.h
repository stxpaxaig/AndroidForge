/* Shared session definition for the JNI wrapper and the QEMU driver. */
#ifndef QEMU_SESSION_H
#define QEMU_SESSION_H

#include <pthread.h>

#define PATH_MAX_LOCAL 4096

typedef enum {
    QEMU_STATE_STARTING = 0,
    QEMU_STATE_RUNNING  = 1,
    QEMU_STATE_STOPPING = 2,
    QEMU_STATE_STOPPED  = 3,
    QEMU_STATE_ERROR    = 4,
} qemu_state_t;

typedef struct {
    pthread_t thread;
    pthread_mutex_t lock;
    volatile qemu_state_t state;
    char flash_path[PATH_MAX_LOCAL];
    char efuse_path[PATH_MAX_LOCAL];
    char rom_path[PATH_MAX_LOCAL];
    int uart_port;
    char error_msg[1024];
} qemu_session_t;

#endif /* QEMU_SESSION_H */
