# Remaining Files And Coverage Map

## Purpose

This file closes the documentation gap for smaller but still important files:

- frozen AIDL API snapshots,
- the AIDL hash file,
- app resources,
- leftover/prebuilt libraries,
- workspace/context files,
- a file-by-file coverage map.

The major runtime features were already covered in the earlier docs.

## AIDL API Snapshots

Files:

- `hardware/interfaces/neuralbind/aidl/aidl_api/android.hardware.neuralbind/1/.hash`
- `hardware/interfaces/neuralbind/aidl/aidl_api/android.hardware.neuralbind/1/android/hardware/neuralbind/INeuralBind.aidl`
- `hardware/interfaces/neuralbind/aidl/aidl_api/android.hardware.neuralbind/1/android/hardware/neuralbind/IInferenceCallback.aidl`
- `hardware/interfaces/neuralbind/aidl/aidl_api/android.hardware.neuralbind/current/android/hardware/neuralbind/INeuralBind.aidl`
- `hardware/interfaces/neuralbind/aidl/aidl_api/android.hardware.neuralbind/current/android/hardware/neuralbind/IInferenceCallback.aidl`

These are generated snapshots for the Stable AIDL interface.

The `1/` directory is the frozen version 1 API. It should be treated as
immutable. The `current/` directory mirrors the current API state used by the
build system to detect API changes.

The snapshot files intentionally contain a warning:

```text
THIS FILE IS IMMUTABLE. DO NOT EDIT IN ANY CASE.
```

Interview importance:

Stable AIDL HALs must preserve compatibility across framework/vendor updates.
If you want to evolve `INeuralBind`, you do not casually edit the frozen
snapshot. You update the source AIDL in a backward-compatible way, run the AIDL
API update flow, freeze a new version when appropriate, and keep old clients
working.

The `.hash` file records the frozen API hash. It is how the build can detect
whether a supposedly frozen interface changed.

## App Resources

Files:

- `packages/apps/NeuralBindChat/res/drawable/ic_launcher.xml`
- `packages/apps/NeuralBindChat/res/values/strings.xml`

`strings.xml` defines:

```xml
<string name="app_name">NeuralBind Chat</string>
```

This is used by the app label in `AndroidManifest.xml`.

`ic_launcher.xml` is a simple vector launcher icon. It is not part of Binder,
HAL, SELinux, or inference behavior. It matters only for packaging and launcher
presentation.

Review note:

The icon uses a default purple fill. This is harmless technically, but it does
not match the app's dark terminal-green visual identity in `MainActivity.kt`.

## TensorFlow Lite Prebuilt

File:

- `vendor/lineage/neuralbind/libs/libtensorflowlite.so`

Observed type:

```text
ELF 64-bit LSB shared object, ARM aarch64, dynamically linked, stripped
```

This looks like a leftover or previous backend dependency. The current native
HAL build explicitly comments that TensorFlow Lite was removed and links
llama.cpp/ggml instead:

```bp
// DELETED libtflite!
static_libs: [
    "libllamacpp_static",
    "libggml_c",
]
```

Review importance:

Unused prebuilts increase repository size, license-review burden, and confusion.
If TensorFlow Lite is truly no longer used, remove the prebuilt or document why
it remains. If it is planned for a fallback backend, add a build module and
design note explaining when it is selected.

Interview answer:

The current NeuralBind HAL path is llama.cpp, not TensorFlow Lite. The TFLite
shared library is not referenced by the visible Soong modules and appears to be
legacy/dead weight unless another unreviewed build file consumes it.

## Workspace And Context Files

Files:

- `AI_CONTEXT.md`
- `.vscode/settings.json`

`AI_CONTEXT.md` records high-level project context:

```text
Project: NeuralBind. OS: LineageOS 23 (Android 16). Target: POCO X6.
```

`.vscode/settings.json` disables a local editor/agent integration:

```json
{
    "kiroAgent.configureMCP": "Disabled"
}
```

These files do not affect Android build output, runtime behavior, HAL
registration, Binder, or SELinux.

## Coverage Map

Covered by `01-end-to-end-flow.md`:

- complete app/test to Binder to HAL to callback flow,
- boot sequence,
- model load sequence,
- inference sequence,
- thread and security overview.

Covered by `02-aidl-contract-and-build.md`:

- `hardware/interfaces/neuralbind/aidl/android/hardware/neuralbind/INeuralBind.aidl`
- `hardware/interfaces/neuralbind/aidl/android/hardware/neuralbind/IInferenceCallback.aidl`
- `hardware/interfaces/neuralbind/aidl/Android.bp`

Covered by `03-native-hal-service.md`:

- `vendor/lineage/neuralbind/qualcomm/service.cpp`
- `vendor/lineage/neuralbind/qualcomm/NeuralBindImpl.h`
- `vendor/lineage/neuralbind/qualcomm/NeuralBindImpl.cpp`
- `vendor/lineage/neuralbind/qualcomm/Android.bp`

Covered by `04-framework-service-and-test-client.md`:

- `frameworks/base/services/core/java/com/android/server/neuralbind/NeuralBindService.java`
- `aosp_modified_files/frameworks/base/services/java/com/android/server/SystemServer.java`
- `aosp_modified_files/frameworks/base/services/core/Android.bp`
- `frameworks/base/cmds/neuralbind-test/src/com/android/commands/neuralbindtest/NeuralBindTest.java`
- `frameworks/base/cmds/neuralbind-test/Android.bp`

Covered by `05-app-client-and-storage.md`:

- `packages/apps/NeuralBindChat/Android.bp`
- `packages/apps/NeuralBindChat/AndroidManifest.xml`
- `packages/apps/NeuralBindChat/src/com/android/neuralbind/chat/ChatViewModel.kt`
- `packages/apps/NeuralBindChat/src/com/android/neuralbind/chat/MainActivity.kt`
- `packages/apps/NeuralBindChat/src/com/android/neuralbind/chat/data/ChatDatabase.kt`
- `packages/apps/NeuralBindChat/src/com/android/neuralbind/chat/data/ChatMessageDao.kt`
- `packages/apps/NeuralBindChat/src/com/android/neuralbind/chat/data/ChatMessageEntity.kt`

Covered by `06-device-integration-selinux-vintf.md`:

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

Covered by `07-review-risks-and-interview.md`:

- cross-project risks,
- design review findings,
- interview questions,
- debug checklist,
- recommended roadmap.

Covered by this file:

- `hardware/interfaces/neuralbind/aidl/aidl_api/**`
- `packages/apps/NeuralBindChat/res/drawable/ic_launcher.xml`
- `packages/apps/NeuralBindChat/res/values/strings.xml`
- `vendor/lineage/neuralbind/libs/libtensorflowlite.so`
- `AI_CONTEXT.md`
- `.vscode/settings.json`

## Not Covered Intentionally

Directory:

- `vendor/lineage/neuralbind/qualcomm/llamacpp/**`

Reason:

This is a vendored third-party inference engine tree, not NeuralBind project glue
code. It is very large and should be studied separately only when the goal is
llama.cpp internals, ggml backends, tokenization internals, or model execution
performance.

For HAL interviews, you usually need to know how NeuralBind calls llama.cpp, not
every internal file inside llama.cpp.

## Final Coverage Assessment

With this file added, the documentation set covers all NeuralBind-owned project
features and all visible non-llamacpp project files.

The only intentionally excluded area is the full third-party `llamacpp` source
tree. That exclusion is appropriate for Android HAL/AOSP interview preparation
unless the interview specifically shifts into inference-engine internals.
