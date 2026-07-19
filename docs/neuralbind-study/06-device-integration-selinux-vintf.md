# Device Integration, SELinux, And VINTF

## Files Covered

- `aosp_modified_files/device/xiaomi/garnet/BoardConfig.mk`
- `aosp_modified_files/device/xiaomi/garnet/device.mk`
- `vendor/lineage/neuralbind/qualcomm/neuralbind-service.rc`
- `vendor/lineage/neuralbind/qualcomm/neuralbind_manifest.xml`
- `vendor/lineage/neuralbind/qualcomm/neuralbind_fcm.xml`
- `aosp_modified_files/device/xiaomi/garnet/sepolicy/public/hal_neuralbind.te`
- `aosp_modified_files/device/xiaomi/garnet/sepolicy/vendor/hal_neuralbind.te`
- `aosp_modified_files/device/xiaomi/garnet/sepolicy/vendor/service_contexts`
- `aosp_modified_files/device/xiaomi/garnet/sepolicy/vendor/file_contexts`
- `aosp_modified_files/device/xiaomi/garnet/sepolicy/private/platform_app.te`

## Product Integration

`device.mk` adds:

```make
PRODUCT_PACKAGES += \
    SimpleBuddyAssistant \
    android.hardware.neuralbind-service \
    NeuralBindChat
```

This includes the native HAL service and app in the product image.

`BoardConfig.mk` adds the framework compatibility matrix:

```make
DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE += \
    vendor/lineage/neuralbind/qualcomm/neuralbind_fcm.xml
```

It also adds system-side SELinux policy directories:

```make
SYSTEM_EXT_PUBLIC_SEPOLICY_DIRS += device/xiaomi/garnet/sepolicy/public
SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += device/xiaomi/garnet/sepolicy/private
```

## Init RC

`neuralbind-service.rc` defines:

```rc
service vendor.neuralbind /vendor/bin/hw/android.hardware.neuralbind-service
    class hal
    user system
    group system
    restart_period 10
```

Purpose:

- starts the vendor HAL binary,
- places it in init's `hal` class,
- runs it as `system:system`,
- restarts after crashes with a 10 second period.

Interview point:

The rc file starts a process. The VINTF manifest declares the service exists.
SELinux allows the process to register and communicate.

## VINTF Manifest

`neuralbind_manifest.xml` declares:

```xml
<hal format="aidl">
    <name>android.hardware.neuralbind</name>
    <version>1</version>
    <fqname>INeuralBind/default</fqname>
</hal>
```

This tells Android the device provides a Stable AIDL HAL instance.

## Framework Compatibility Matrix

`neuralbind_fcm.xml` declares:

```xml
<hal format="aidl" optional="true">
    <name>android.hardware.neuralbind</name>
    <version>1</version>
    <interface>
        <name>INeuralBind</name>
        <instance>default</instance>
    </interface>
</hal>
```

Because it is `optional="true"`, framework compatibility does not strictly
require the HAL on every target that uses the matrix. For this product, the
package is still included.

## Public SELinux Policy

`public/hal_neuralbind.te`:

```te
hal_attribute(neuralbind);
type hal_neuralbind_service, hal_service_type, service_manager_type;
```

This creates the HAL attribute and service manager type visible across policy
partitions.

Why public:

Both system-side clients and vendor-side server policy need to agree on the HAL
attribute and service type.

## Vendor SELinux Policy

`vendor/hal_neuralbind.te` defines:

```te
type hal_neuralbind_default, domain;
type hal_neuralbind_default_exec, exec_type, vendor_file_type, file_type;
hal_server_domain(hal_neuralbind_default, hal_neuralbind)
init_daemon_domain(hal_neuralbind_default)
hal_attribute_service(hal_neuralbind, hal_neuralbind_service)
binder_use(hal_neuralbind_default)
binder_call(hal_neuralbind_default, platform_app)
allow hal_neuralbind_default vendor_data_file:dir { search getattr };
allow hal_neuralbind_default vendor_data_file:file { read open getattr map };
```

Meaning:

- `hal_neuralbind_default` is the runtime process domain.
- `hal_neuralbind_default_exec` labels the binary.
- `hal_server_domain` marks it as the NeuralBind HAL server.
- `init_daemon_domain` allows init to transition into that domain.
- `hal_attribute_service` lets it register the NeuralBind HAL service.
- `binder_use` permits Binder driver/service manager use.
- `binder_call(... platform_app)` allows callbacks into the app domain.
- vendor data read/map permissions allow loading `/data/vendor/*.gguf`.

## File Contexts

`file_contexts` labels the binary:

```text
/(vendor|system/vendor)/bin/hw/android\.hardware\.neuralbind-service \
    u:object_r:hal_neuralbind_default_exec:s0
```

Without this label, init may not transition the process into the correct HAL
domain.

## Service Contexts

`service_contexts` labels the Binder service:

```text
android.hardware.neuralbind.INeuralBind/default \
    u:object_r:hal_neuralbind_service:s0
```

Without this, service manager registration or client lookup can fail due to
SELinux.

## Platform App Policy

`private/platform_app.te`:

```te
hal_client_domain(platform_app, hal_neuralbind)
binder_call(platform_app, hal_neuralbind_server)
```

This allows platform apps to act as NeuralBind HAL clients.

Review point:

This is necessary for the current direct app-to-HAL architecture. If the app
moved behind a framework service, policy could be tightened so only
`system_server` talks to the HAL.

## Security Review

Strengths:

- Dedicated HAL domain.
- Dedicated service type.
- Binary file context is specific.
- Model file access is limited to vendor data file type.
- Binder client and callback permissions are explicit.

Risks:

- `platform_app` is broad. All platform app domain members may gain this HAL
  access, not only `NeuralBindChat`.
- HAL reads model paths supplied by clients.
- Callback permission to `platform_app` is broad.
- Running as `system` may be more privilege than needed.
- No visible custom file type for NeuralBind model files.

Better policy:

- Create a dedicated app domain for `NeuralBindChat` if possible.
- Create a dedicated model file type such as `neuralbind_model_file`.
- Restrict readable paths to `/data/vendor/neuralbind(/.*)?`.
- Route app traffic through `system_server`.

## Debugging

VINTF:

```bash
adb shell lshal | grep -i neural
adb shell vintf
adb shell cat /vendor/etc/vintf/manifest.xml | grep -i neural -A5 -B2
```

SELinux labels:

```bash
adb shell ls -lZ /vendor/bin/hw/android.hardware.neuralbind-service
adb shell ls -lZ /data/vendor/gemma.gguf
adb shell cat /vendor/etc/selinux/vendor_file_contexts | grep neural
adb shell cat /vendor/etc/selinux/vndservice_contexts | grep neural
```

Denials:

```bash
adb logcat -b all | grep avc
adb shell dmesg | grep avc
```

## Interview Questions

Easy:

- What does an init rc file do?
- What does file_contexts label?
- What is a VINTF manifest?

Medium:

- Why do HALs need service_contexts?
- What does `hal_server_domain` do?
- Why is `hal_client_domain(platform_app, hal_neuralbind)` needed here?

Hard:

- Tighten SELinux so only NeuralBindChat can access the HAL.
- Explain how VINTF catches framework/vendor mismatches.
- Design a safer policy for model files.
