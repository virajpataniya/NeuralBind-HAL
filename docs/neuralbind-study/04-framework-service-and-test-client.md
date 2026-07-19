# Framework Service And Test Client

## Files Covered

- `frameworks/base/services/core/java/com/android/server/neuralbind/NeuralBindService.java`
- `aosp_modified_files/frameworks/base/services/java/com/android/server/SystemServer.java`
- `aosp_modified_files/frameworks/base/services/core/Android.bp`
- `frameworks/base/cmds/neuralbind-test/src/com/android/commands/neuralbindtest/NeuralBindTest.java`
- `frameworks/base/cmds/neuralbind-test/Android.bp`

## `NeuralBindService.java`

This class extends `SystemService`, so it runs inside `system_server`.

Responsibilities:

- wait for the declared HAL service,
- store the Java AIDL proxy,
- enforce a framework permission before calling the HAL,
- forward `loadModel` and `submitPrompt` calls.

Boot connection:

```java
IBinder binder = ServiceManager.waitForDeclaredService(
    "android.hardware.neuralbind.INeuralBind/default");
mHalService = INeuralBind.Stub.asInterface(binder);
```

Permission gate:

```java
getContext().enforceCallingOrSelfPermission(
    "android.permission.USE_NEURALBIND",
    "Denied");
```

Important issue:

The service does not call `publishBinderService(...)`. It also does not define a
framework AIDL interface for apps. That means it is started by `SystemServer`,
but ordinary clients cannot discover and call it as a framework service. The app
currently talks directly to the HAL instead.

## `SystemServer.java`

The modified `SystemServer` starts the service:

```java
mSystemServiceManager.startService(
    com.android.server.neuralbind.NeuralBindService.class);
```

This places NeuralBind inside Android's normal boot lifecycle.

The service is started after many core services. Its own HAL connection occurs
at `PHASE_SYSTEM_SERVICES_READY`.

Interview point:

`SystemService` classes are lifecycle-managed by `SystemServiceManager`; they are
not automatically public Binder APIs. Publishing and permission design are
separate decisions.

## `services/core/Android.bp`

The framework service build adds:

```bp
"android.hardware.neuralbind-V1-java",
```

to `services.core` static libs.

Why needed:

`NeuralBindService.java` imports and calls generated Java AIDL classes from the
HAL interface. Without this dependency, framework services cannot compile.

## `NeuralBindTest.java`

The command-line test client directly calls the HAL:

```java
IBinder binder = ServiceManager.waitForDeclaredService(SERVICE_NAME);
INeuralBind service = INeuralBind.Stub.asInterface(binder);
service.loadModel(MODEL_PATH);
service.submitPrompt(prompt, callback);
latch.await();
```

It uses:

```java
CountDownLatch latch = new CountDownLatch(1);
```

The callback prints chunks and counts down when `isFinished` is true.

This is useful for bring-up because it bypasses UI and app state. It tests:

- service registration,
- Binder connectivity,
- model load,
- callback streaming,
- end-to-end inference.

## Test Build

`neuralbind-test/Android.bp` defines an installable `java_library`:

```bp
java_library {
    name: "neuralbind-test",
    installable: true,
    srcs: ["src/**/*.java"],
    libs: ["framework"],
    static_libs: ["android.hardware.neuralbind-V1-java"],
}
```

## Design Review

Strengths:

- Framework service attempts to centralize HAL access.
- Permission enforcement is present at framework level.
- Test client is simple and valuable for bring-up.
- `waitForDeclaredService` is correct for declared AIDL HALs.

Weaknesses:

- Framework service is not published to clients.
- `USE_NEURALBIND` permission is referenced, but the definition is not visible in
  the reviewed files.
- Direct HAL access by app bypasses framework permission checks.
- No Binder death handling for HAL service.
- No retry/reconnect if HAL crashes after boot.
- Public Java methods on `SystemService` do not create IPC APIs by themselves.

## Better Framework Architecture

Recommended shape:

```text
app
  -> INeuralBindManager.aidl framework API
  -> NeuralBindManagerService in system_server
  -> permission and AppOps checks
  -> HAL proxy
  -> vendor HAL
```

Benefits:

- apps never talk directly to vendor HAL,
- framework owns permissions and policy,
- HAL can remain vendor-private,
- easier dumpsys/debugging,
- better lifecycle handling.

## Debugging

Framework logs:

```bash
adb logcat | grep NeuralBindService
```

System server startup:

```bash
adb logcat | grep StartNeuralBindService
adb shell dumpsys activity service
```

Test client:

```bash
adb shell neuralbind-test "Write a short haiku about Android HALs"
```

If command wrapper is not generated, inspect installed jar/binary path in the
build output and run with `app_process` or add a `java_binary` wrapper.

## Interview Questions

Easy:

- What is `SystemService`?
- What does `ServiceManager.waitForDeclaredService` do?
- Why does framework need the generated AIDL Java library?

Medium:

- Why is starting a `SystemService` different from publishing a Binder service?
- How should a framework service handle HAL death?
- Where should permissions be enforced?

Hard:

- Redesign this so third-party apps can safely request inference.
- Explain why direct app-to-HAL access is risky.
- Add `dumpsys neuralbind` support.
