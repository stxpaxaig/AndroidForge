# Android JNI driver for the classic ESP32 QEMU backend.
# Built as part of QEMU's Meson build so it has direct access to QEMU's
# configured headers, generated QAPI headers, and all dependencies.
#
# Produces: libesp32_qemu_android.so (ARM64 Android shared library)
# JNI contract: dev.virtualesp32.ClassicEsp32QemuBackend

# JNI headers from the NDK sysroot
ndk_sysroot = '/home/hatch/workspace/virtualesp32/native/android-ndk-r27c/toolchains/llvm/prebuilt/linux-x86_64/sysroot'
jni_inc = include_directories(ndk_sysroot / 'usr' / 'include')

# The JNI driver sources
jni_sources = files(
  'esp32_qemu_jni.c',
  'qemu_driver_real.c',
)

# Build the shared library, linking against the QEMU objects.
# We reuse the same libraries that the xtensa system emulator links.
# Note: This must come after the main libraries are defined.

# Find the xtensa softmmu libraries from the parent scope
# (they're defined in the main meson.build before this subdir is processed)

esp32_jni = shared_library('esp32_qemu_android',
  jni_sources,
  include_directories: [jni_inc],
  c_args: ['-DQEMU_CORE_AVAILABLE'],
  # Link against QEMU's compiled objects via the static archive
  # built from the successful qemu-system-xtensa build
  link_args: [
    meson.current_build_dir() / '..' / 'libqemu_xtensa_softmmu.a',
  ],
  dependencies: [
    dependency('glib-2.0'),
    dependency('gobject-2.0'),
    dependency('gio-2.0'),
  ],
  link_with: [],
  install: false,
  name_prefix: 'lib',
  name_suffix: 'so',
)
