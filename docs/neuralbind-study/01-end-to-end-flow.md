# End-to-End Flow

## Big Picture

NeuralBind is an Android native HAL project for on-device AI inference.

The intended flow is:

```text
NeuralBindChat app / test client
  -> Java Stable AIDL proxy
  -> Binder driver
  -> vendor native HAL service
  -> NeuralBindImpl
  -> llama.cpp model/context/sampler
  -> callback Binder object
  -> app/test client receives streamed text
```

There is also a framework service:

```text
SystemServer
  -> NeuralBindService
  -> ServiceManager.waitForDeclaredService(...)
  -> android.hardware.neuralbind.INeuralBind/default
```

Important design observation: the current app and test client talk directly to
the HAL service. `NeuralBindService` exists and connects to the HAL, but it does
not publish a framework Binder service for apps to use.

## Boot Flow

1. Android init parses `neuralbind-service.rc`.
2. The rc file starts:

   ```text
   service vendor.neuralbind /vendor/bin/hw/android.hardware.neuralbind-service
   ```

3. The process enters `service.cpp`.
4. `service.cpp` sets the NDK Binder thread pool size to 4.
5. It creates `NeuralBindImpl` using `ndk::SharedRefBase::make`.
6. It builds the service name:

   ```cpp
   NeuralBindImpl::descriptor + "/default"
   ```

7. It registers the service with `AServiceManager_addService`.
8. It joins the Binder thread pool.
9. Framework and clients can now call
   `android.hardware.neuralbind.INeuralBind/default`.

## Model Load Flow

1. Client calls:

   ```aidl
   void loadModel(in String modelPath);
   ```

2. Binder copies the Java/Kotlin string across process boundary.
3. Native HAL receives `NeuralBindImpl::loadModel(const std::string& path)`.
4. HAL calls `llama_backend_init()`.
5. HAL creates default `llama_model_params`.
6. HAL calls `llama_load_model_from_file(path.c_str(), model_params)`.
7. On success, HAL stores the raw pointer in `mModel`.
8. HAL creates `llama_context_params`.
9. HAL sets `ctx_params.n_ctx = 2048`.
10. HAL calls `llama_new_context_with_model(mModel, ctx_params)`.
11. On success, HAL stores the raw pointer in `mContext`.
12. HAL returns `ScopedAStatus::ok()`.

## Prompt Inference Flow

1. Client creates an `IInferenceCallback.Stub`.
2. Client calls:

   ```aidl
   oneway void submitPrompt(in String prompt, in IInferenceCallback callback);
   ```

3. Because the method is `oneway`, the caller does not wait for an inference
   result.
4. Native HAL receives `NeuralBindImpl::submitPrompt`.
5. HAL starts a detached `std::thread`.
6. Worker checks whether `mModel` and `mContext` are non-null.
7. Worker frees the old context and creates a fresh 2048-token context.
8. Worker obtains the model vocab with `llama_model_get_vocab`.
9. Worker tokenizes the prompt using `llama_tokenize`.
10. Worker builds a `llama_batch` for prompt evaluation.
11. Worker calls `llama_decode` to evaluate the prompt.
12. Worker creates a greedy sampler.
13. Worker loops up to 512 generated tokens.
14. For each token:

    - sample token,
    - accept token into sampler,
    - stop if EOS,
    - detokenize to a text piece,
    - call `callback->onResponse(piece, false)`,
    - decode the generated token for the next iteration.

15. Worker frees sampler and batch.
16. Worker calls:

    ```cpp
    callback->onResponse("[DONE]", true);
    ```

## Data Ownership

`modelPath`:

- Owned by caller before Binder.
- Copied into HAL as `std::string`.
- Used as a filesystem path by llama.cpp.
- The loaded model is stored in `mModel`.

`prompt`:

- Owned by caller before Binder.
- Copied into HAL as `std::string`.
- Captured by value into detached worker thread.

`callback`:

- Created by caller process.
- Passed across Binder as a remote object reference.
- Captured by `std::shared_ptr` in the worker thread.
- Used by HAL to send streaming results back to caller.

`mModel` and `mContext`:

- Raw llama.cpp pointers owned by `NeuralBindImpl`.
- Current destructor does not free them.
- `submitPrompt` frees and recreates `mContext`.

## Threading Model

Threads involved:

- client UI/coroutine/test thread,
- client Binder callback thread,
- HAL Binder thread pool thread,
- HAL detached worker thread,
- `system_server` thread for `NeuralBindService` boot connection.

Major concern: `mContext` is shared mutable state accessed from detached worker
threads without a mutex. Two simultaneous prompts can race, free the context
under each other, or corrupt llama.cpp state.

## Security Boundary

The strongest security boundary should be:

```text
app -> framework system service -> permission check -> HAL
```

Current reality:

```text
privileged app -> HAL directly
test command -> HAL directly
system service -> HAL, but not published as public API
```

SELinux currently allows `platform_app` to be a HAL client and allows the HAL to
callback into `platform_app`. This enables the current app design, but it is
less clean than routing app traffic through a framework Binder service.

## Debugging Flow

Check service registration:

```bash
adb shell service list | grep -i neural
adb shell cmd servicemanager list | grep -i neural
adb shell ps -A | grep -i neural
```

Check HAL logs:

```bash
adb logcat | grep -E "NeuralBindHAL|NeuralBindService|ChatViewModel"
```

Check SELinux denials:

```bash
adb logcat -b all | grep avc
adb shell dmesg | grep avc
```

Check model file:

```bash
adb shell ls -l /data/vendor/gemma.gguf
adb shell ls -lZ /data/vendor/gemma.gguf
```

## Interview Summary

The strongest explanation:

NeuralBind defines a Stable AIDL HAL for model loading and prompt submission.
The native vendor service registers an NDK Binder implementation at boot, loads a
GGUF model through llama.cpp, and streams generated tokens back through a Binder
callback. The main engineering challenges are compatibility, SELinux policy,
thread safety, model memory lifetime, Binder callback death, and routing access
through a proper framework service rather than letting clients talk directly to
the vendor HAL.
