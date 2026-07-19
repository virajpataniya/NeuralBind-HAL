# Review Risks And Interview Prep

## Highest-Priority Findings

1. `NeuralBindService` is not published as a Binder service.

   It starts in `system_server` and connects to the HAL, but clients currently
   cannot use it as a normal framework service. The app and test client call the
   HAL directly.

2. Direct app-to-HAL access weakens the framework security model.

   The app is privileged and platform-signed, and SELinux allows `platform_app`
   to call the HAL. A more Android-native model is app -> framework service ->
   HAL.

3. Native HAL threading is unsafe for concurrent prompts.

   `submitPrompt` starts detached threads. Each worker can free and recreate
   shared `mContext` without locking. Concurrent requests can race.

4. llama.cpp resources leak.

   `mModel` and final `mContext` are not freed in the default destructor.
   Loading a new model does not free the old model/context first.

5. Error handling is too thin.

   The AIDL API has no explicit result objects, no `onError`, no request ID, and
   no cancellation.

6. Model path handling is security-sensitive.

   Clients provide raw filesystem paths. The HAL should validate paths and
   SELinux should restrict model files to a dedicated type/directory.

7. Binder callback death is not handled.

   Long-running inference should detect client death and stop work.

## Strong Architecture Answer

NeuralBind uses Stable AIDL to define a vendor HAL for local AI inference. The
native vendor service registers `INeuralBind/default` through the NDK service
manager, loads GGUF models with llama.cpp, and streams token chunks using a
reverse Binder callback. The framework service begins to wrap this HAL, but the
current app still talks directly to the HAL. A production design should publish a
framework manager service, enforce permissions there, use a thread-safe request
queue in the HAL, handle Binder death, and lock down model file access with
SELinux.

## Must-Know Concepts

Stable AIDL:

- versioned,
- frozen,
- suitable for framework/vendor boundary,
- generates Java and NDK bindings.

Binder:

- copies strings through Parcels,
- passes callback objects by Binder reference,
- uses thread pools on server side,
- needs death handling for remote clients.

VINTF:

- manifest declares what vendor provides,
- compatibility matrix declares what framework expects,
- protects system/vendor compatibility.

SELinux:

- labels service binary,
- labels service manager entry,
- separates HAL server domain,
- grants client/server Binder calls,
- controls model file access.

## Interview Drill

Easy questions:

- What is a HAL?
- What is AIDL?
- What does `oneway` do?
- What does `@VintfStability` mean?
- Why does the HAL use `ScopedAStatus`?

Medium questions:

- Why is direct app-to-HAL access discouraged?
- Why do we need both init rc and VINTF manifest?
- What is the role of `service_contexts`?
- How does the callback stream data back to the app?
- Why should inference not run on a Binder thread?

Hard questions:

- Design a safe multi-client inference service.
- How would you handle callback death?
- How would you add cancellation without breaking version 1?
- How would you prevent model path attacks?
- How do you avoid Binder overhead during token streaming?

Google-style follow-ups:

- The HAL crashes during generation. How does the framework recover?
- Two apps submit prompts at the same time. What should happen?
- The user switches models while inference is running. What state machine do you
  design?
- A model file triggers SELinux denial. Walk through the debug process.
- You need to support Android OTA where framework updates but vendor partition
  stays old. How does your AIDL design survive?

## Recommended Fix Roadmap

Phase 1:

- Add destructor cleanup for llama resources.
- Add mutex or single worker queue around `mModel` and `mContext`.
- Reject concurrent prompt requests until safe.
- Validate callback is non-null.
- Stop logging full prompts in production builds.

Phase 2:

- Publish a real framework Binder service.
- Move app to framework API.
- Define `android.permission.USE_NEURALBIND`.
- Add HAL reconnect and Binder death handling.
- Add `dumpsys neuralbind`.

Phase 3:

- Add request IDs and cancellation.
- Add structured error callbacks.
- Add dedicated model file SELinux type.
- Add generation parameter parcelables.
- Add CTS/VTS-style tests.

## Debug Checklist

Service not found:

```bash
adb shell ps -A | grep neural
adb shell cmd servicemanager list | grep neural
adb logcat | grep NeuralBindHAL
adb logcat -b all | grep avc
```

Model load fails:

```bash
adb shell ls -lZ /data/vendor/gemma.gguf
adb logcat | grep "Failed to load GGUF"
adb logcat -b all | grep avc
```

No streamed response:

```bash
adb logcat | grep -E "submitPrompt|Worker Thread|onResponse|ChatViewModel"
adb shell pidof android.hardware.neuralbind-service
```

Native crash:

```bash
adb logcat -b crash
adb shell ls -l /data/tombstones
```

Performance issue:

```bash
adb shell simpleperf top -p $(adb shell pidof android.hardware.neuralbind-service)
adb shell dumpsys thermalservice
```
