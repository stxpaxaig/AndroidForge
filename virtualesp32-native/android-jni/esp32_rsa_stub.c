/*
 * ESP32 RSA accelerator stub for Android builds without libgcrypt.
 *
 * Registers the "misc.esp32.rsa" QOM type so the ESP32 machine can
 * initialize. The registers exist and are readable/writable, but
 * actual RSA cryptographic operations are not performed.
 *
 * This is sufficient for firmware that does not use the RSA accelerator
 * (e.g., UART hello-world tests). The CPU, UART, SPI flash, and other
 * peripherals function normally.
 */

#include "qemu/osdep.h"
#include "qemu/log.h"
#include "hw/sysbus.h"
#include "hw/misc/esp32_rsa.h"
#include "hw/qdev-properties.h"

#define ESP32_RSA_REGS_SIZE 0x100

static uint64_t esp32_rsa_read(void *opaque, hwaddr addr, unsigned size)
{
    (void)opaque; (void)addr; (void)size;
    /* Return 0 for all registers - RSA not actually computed */
    return 0;
}

static void esp32_rsa_write(void *opaque, hwaddr addr, uint64_t value,
                            unsigned size)
{
    /* Accept writes but do nothing - RSA operations are stubbed */
}

static const MemoryRegionOps esp32_rsa_ops = {
    .read = esp32_rsa_read,
    .write = esp32_rsa_write,
    .endianness = DEVICE_LITTLE_ENDIAN,
};

static void esp32_rsa_reset_hold(Object *obj, ResetType type)
{
    /* Nothing to reset in stub */
}

static void esp32_rsa_init(Object *obj)
{
    Esp32RsaState *s = ESP32_RSA(obj);
    SysBusDevice *sbd = SYS_BUS_DEVICE(obj);

    memory_region_init_io(&s->iomem, obj, &esp32_rsa_ops, s,
                          TYPE_ESP32_RSA, ESP32_RSA_REGS_SIZE);
    sysbus_init_mmio(sbd, &s->iomem);
}

static void esp32_rsa_class_init(ObjectClass *klass, void *data)
{
    ResettableClass *rc = RESETTABLE_CLASS(klass);
    rc->phases.hold = esp32_rsa_reset_hold;
}

static const TypeInfo esp32_rsa_info = {
    .name = TYPE_ESP32_RSA,
    .parent = TYPE_SYS_BUS_DEVICE,
    .instance_size = sizeof(Esp32RsaState),
    .instance_init = esp32_rsa_init,
    .class_init = esp32_rsa_class_init,
};

static void esp32_rsa_register_types(void)
{
    type_register_static(&esp32_rsa_info);
}

type_init(esp32_rsa_register_types);
