# AIDL Contract And Build

## Files Covered

- `hardware/interfaces/neuralbind/aidl/android/hardware/neuralbind/INeuralBind.aidl`
- `hardware/interfaces/neuralbind/aidl/android/hardware/neuralbind/IInferenceCallback.aidl`
- `hardware/interfaces/neuralbind/aidl/Android.bp`

## Purpose

These files define the stable HAL API. They are the framework/vendor contract.
Everything else depends on this interface shape.

## `INeuralBind.aidl`

The interface exposes two calls:

```aidl
void loadModel(in String modelPath);
oneway void submitPrompt(in String prompt, in IInferenceCallback callback);
```

`loadModel` is synchronous. The caller waits until the HAL returns a Binder
status. It loads the model into the HAL process.

`submitPrompt` is asynchronous because of `oneway`. The caller does not wait for
generated text. Instead, text is streamed through `IInferenceCallback`.

## `IInferenceCallback.aidl`

The callback exposes:

```aidl
oneway void onResponse(in String content, in boolean isFinished);
```

This is reverse Binder IPC. The client gives the HAL a Binder object, then the
HAL calls back into the client process.

`content` may be a partial chunk or final marker.

`isFinished` tells the client when it can stop showing streaming UI and persist
the final response.

## `@VintfStability`

Both interfaces use:

```aidl
@VintfStability
```

This means the interface is intended to be stable across framework and vendor
partitions. This is a Treble/VINTF compatibility commitment.

Interview answer:

Stable AIDL is used because HALs cross the system/vendor boundary. The framework
may be updated independently from vendor code, so Android needs a frozen,
versioned interface that both sides agree on.

## `Android.bp`

The `aidl_interface` module is:

```bp
aidl_interface {
    name: "android.hardware.neuralbind",
    vendor_available: true,
    stability: "vintf",
    backend: {
        java: { enabled: true },
        ndk: { enabled: true },
    },
    versions_with_info: [
        {
            version: "1",
            imports: [],
        },
    ],
    owner: "lineage",
    frozen: true,
}
```

Important points:

- `vendor_available: true` lets vendor modules link against the generated
  interface.
- `stability: "vintf"` makes the generated API usable as a stable HAL.
- Java backend is needed by framework, app, and Java test code.
- NDK backend is needed by the native vendor service.
- `frozen: true` means version 1 is locked and should not be casually changed.

## Generated Artifacts

Soong generates modules such as:

- `android.hardware.neuralbind-V1-java`
- `android.hardware.neuralbind-V1-ndk`

These are consumed by:

- `frameworks/base/services/core/Android.bp`
- `frameworks/base/cmds/neuralbind-test/Android.bp`
- `packages/apps/NeuralBindChat/Android.bp`
- `vendor/lineage/neuralbind/qualcomm/Android.bp`

## Data Flow

`String` values are copied through Binder Parcels. This is fine for model paths
and modest prompts, but it is not appropriate for very large payloads.

The callback is not copied as data. Binder passes a reference to a remote Binder
object.

## Design Review

Strengths:

- Clean minimal API.
- Stable AIDL is the modern HAL direction.
- Callback streaming fits token generation.
- Java and NDK backends match the project structure.

Weaknesses:

- No explicit success/error result for `loadModel`.
- No `onError` callback.
- No cancellation.
- No request ID.
- No session handle.
- No explicit concurrency behavior.
- Path-based model loading creates a security-sensitive API.

## Recommended Evolution

For version 2, consider:

```aidl
parcelable InferenceRequest {
    String prompt;
    int maxTokens;
    float temperature;
    String sessionId;
}
```

and:

```aidl
void cancel(in String requestId);
ModelStatus getModelStatus();
```

Callback could become:

```aidl
oneway void onToken(in String requestId, in String token);
oneway void onError(in String requestId, in int code, in String message);
oneway void onComplete(in String requestId);
```

## Interview Questions

Easy:

- What does AIDL generate?
- What does `oneway` mean?
- Why is the callback also an AIDL interface?

Medium:

- Why does a HAL need `@VintfStability`?
- What is the difference between Java and NDK AIDL backends?
- Why is `frozen: true` important?

Hard:

- How do you change this API without breaking vendor compatibility?
- How would you design cancellation?
- How would you prevent malicious model paths?
