# Native HAL Service

## Files Covered

- `vendor/lineage/neuralbind/qualcomm/service.cpp`
- `vendor/lineage/neuralbind/qualcomm/NeuralBindImpl.h`
- `vendor/lineage/neuralbind/qualcomm/NeuralBindImpl.cpp`
- `vendor/lineage/neuralbind/qualcomm/Android.bp`

## Purpose

This layer is the real vendor HAL implementation. It receives Binder calls from
clients, loads a llama.cpp model, runs inference, and streams output through an
AIDL callback.

## `service.cpp`

Main responsibilities:

- create the HAL object,
- register it with the AIDL service manager,
- configure the Binder thread pool,
- keep the process alive.

Key flow:

```cpp
ABinderProcess_setThreadPoolMaxThreadCount(4);
auto service = ndk::SharedRefBase::make<NeuralBindImpl>();
std::string instance = NeuralBindImpl::descriptor + "/default";
AServiceManager_addService(service->asBinder().get(), instance.c_str());
ABinderProcess_joinThreadPool();
```

Why this exists:

Every binderized native HAL needs a process entry point. The AIDL file only
generates interfaces; `service.cpp` makes an actual runtime service available.

Thread pool:

The service allows up to 4 Binder threads. This does not mean inference should
run on Binder threads. Binder threads should validate, enqueue, and return.

## `NeuralBindImpl.h`

`NeuralBindImpl` inherits from generated `BnNeuralBind`:

```cpp
class NeuralBindImpl : public BnNeuralBind
```

This means it is the server-side implementation of `INeuralBind`.

Public methods:

```cpp
ndk::ScopedAStatus loadModel(const std::string& path) override;
ndk::ScopedAStatus submitPrompt(
    const std::string& prompt,
    const std::shared_ptr<IInferenceCallback>& callback) override;
```

Private state:

```cpp
llama_model* mModel = nullptr;
llama_context* mContext = nullptr;
```

Design implication:

The service has one global model and one global context. This is simple, but it
does not naturally support multiple users, multiple sessions, parallel prompts,
or safe model switching.

## `loadModel`

Flow:

1. Log model path.
2. Initialize llama backend.
3. Create default model params.
4. Load GGUF model from file.
5. Store pointer in `mModel`.
6. Create context params.
7. Set context window to 2048.
8. Create `llama_context`.
9. Store pointer in `mContext`.
10. Return `ScopedAStatus::ok()`.

Error handling:

- If model load fails, returns `EX_ILLEGAL_ARGUMENT`.
- If context creation fails, returns service-specific error 1.

Review findings:

- Calling `llama_backend_init()` every `loadModel` may be wrong or wasteful
  depending on llama.cpp lifecycle expectations.
- Existing `mModel` and `mContext` are not freed before loading a new model.
- Destructor is default and does not free llama resources.
- `modelPath` is trusted too much.

## `submitPrompt`

Flow:

1. Log prompt.
2. Start a detached worker thread.
3. Worker checks model/context.
4. Worker frees and recreates `mContext`.
5. Worker gets vocab.
6. Worker tokenizes prompt.
7. Worker creates and fills a `llama_batch`.
8. Worker evaluates prompt with `llama_decode`.
9. Worker creates greedy sampler.
10. Worker generates up to 512 tokens.
11. For each token, detokenize and call callback.
12. Worker frees sampler and batch.
13. Worker sends `[DONE]` with `isFinished = true`.

## Threading Review

Current implementation starts:

```cpp
std::thread([this, prompt, callback]() { ... }).detach();
```

Strength:

- `submitPrompt` returns quickly and does not block the Binder thread.

Risks:

- Detached thread captures raw `this`.
- No lifecycle coordination if service object is destroyed.
- No mutex protects `mModel` or `mContext`.
- Two prompts can run concurrently and both mutate/free `mContext`.
- `loadModel` can run while inference is active.
- No cancellation or join on shutdown.

Production recommendation:

- Use a worker queue or single-thread executor.
- Protect model/context with a mutex.
- Reject concurrent requests or create per-request contexts.
- Track request IDs.
- Add cancellation.
- Register Binder death recipient for callback.

## Memory Management

Resources allocated:

- `llama_model*` from `llama_load_model_from_file`.
- `llama_context*` from `llama_new_context_with_model`.
- `llama_batch` from `llama_batch_init`.
- `llama_sampler*` from `llama_sampler_init_greedy`.

Resources freed:

- batch is freed in normal paths and prompt evaluation failure path.
- sampler is freed in normal completion.
- context is freed before each new prompt.

Missing or risky:

- `mModel` is never freed.
- final `mContext` is never freed in destructor.
- if generation `llama_decode` fails after sampler creation, cleanup still
  reaches sampler/batch free, which is good.
- if tokenization fails before batch init, no batch cleanup is needed.
- callback errors are ignored.

## Binder Callback Behavior

The HAL calls:

```cpp
callback->onResponse(piece, false);
callback->onResponse("[DONE]", true);
```

Each callback call is another Binder transaction from the HAL process back into
the client process.

Because `onResponse` is `oneway`, HAL does not receive an application-level
reply. If the client is dead, Binder status may report failure, but current code
does not inspect callback return status.

## Performance Review

Strengths:

- Model is loaded once and reused.
- Streaming avoids one huge Binder response.
- Context window is bounded.

Costs:

- Context is recreated for every prompt.
- Greedy sampler limits output quality.
- Callback per token or tiny piece can create many Binder transactions.
- No batching across clients.
- No thermal or power awareness.
- No timeout.

Potential optimizations:

- Stream chunks of several tokens.
- Keep per-session contexts.
- Add configurable generation parameters.
- Use a bounded request queue.
- Add simpleperf and Perfetto profiling around token generation.

## Build File

`Android.bp` defines:

```bp
cc_binary {
    name: "android.hardware.neuralbind-service",
    relative_install_path: "hw",
    vendor: true,
    srcs: ["service.cpp", "NeuralBindImpl.cpp"],
    shared_libs: [
        "libbase",
        "libbinder_ndk",
        "liblog",
        "libutils",
        "android.hardware.neuralbind-V1-ndk",
    ],
    static_libs: [
        "libllamacpp_static",
        "libggml_c",
    ],
    vintf_fragments: ["neuralbind_manifest.xml"],
    init_rc: ["neuralbind-service.rc"],
    cpp_std: "gnu++20",
}
```

This installs a vendor HAL binary under `/vendor/bin/hw`, includes its init rc,
and contributes its VINTF manifest fragment.

## Debugging

Logs:

```bash
adb logcat | grep NeuralBindHAL
```

Service:

```bash
adb shell ps -A | grep neural
adb shell cmd servicemanager list | grep neural
```

Model file:

```bash
adb shell ls -lZ /data/vendor/gemma.gguf
```

Native crash:

```bash
adb logcat -b crash
adb shell tombstoned
adb shell ls /data/tombstones
```

Performance:

```bash
adb shell simpleperf record -p $(adb shell pidof android.hardware.neuralbind-service)
adb shell perfetto -o /data/misc/perfetto-traces/neuralbind.pftrace -t 10s sched freq idle binder_driver
```

## Interview Questions

Easy:

- Why does the service call `AServiceManager_addService`?
- What is `BnNeuralBind`?
- Why is `ScopedAStatus` returned?

Medium:

- Why use a worker thread after a `oneway` call?
- What is wrong with detached threads in a HAL?
- What happens when the callback process dies?

Hard:

- Design a thread-safe inference queue for this HAL.
- How would you support multiple concurrent chats?
- How would you manage model/context lifetime safely?
