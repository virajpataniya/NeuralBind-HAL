# Why NeuralBind Uses llama.cpp Instead Of MediaPipe / LiteRT / Other Paths

## Decision

For this project, the current choice of embedding llama.cpp inside a native
Stable AIDL HAL is a reasonable learning and platform-engineering approach.

It is especially good when the goal is to master:

- Android HAL design,
- Binder and callback streaming,
- vendor service integration,
- SELinux,
- VINTF,
- native model lifecycle,
- low-level LLM runtime behavior.

For a production consumer app in 2026, LiteRT-LM or AICore/Gemini Nano may be a
better default depending on model choice, device support, and product
requirements. But for a custom AOSP/LineageOS HAL project, llama.cpp is still the
most direct and controllable route.

## Evaluation Criteria

NeuralBind is not just an app. It is an Android platform/HAL project.

So the criteria are:

- Can it run inside a native vendor process?
- Can it be built by Soong/AOSP?
- Can it work offline?
- Can it load open local models?
- Can it stream tokens through Binder?
- Can it be debugged at native/system level?
- Can we control memory, threading, and model lifecycle?
- Can it work without Google Play services?
- Can it teach HAL/AOSP interview concepts?

## Option 1: llama.cpp Inside A Native HAL

Architecture:

```text
App / test / framework
  -> Stable AIDL
  -> vendor native HAL
  -> llama.cpp / ggml
  -> local GGUF model
```

Strengths:

- Native C/C++ runtime fits vendor HAL.
- Easy to expose through Stable AIDL.
- Works with local GGUF files.
- Supports many open model families.
- Strong control over tokenization, sampling, context, KV cache, and streaming.
- Minimal dependency story compared with larger app SDK stacks.
- Excellent for learning AOSP, Binder, HAL, native memory, and SELinux.
- Can be compiled directly into the vendor service.

Weaknesses:

- You own lifecycle, threading, memory, and safety.
- CPU-only path can be slow and thermally expensive on phones.
- GPU/NPU acceleration is not automatic.
- You own model distribution and validation.
- No built-in Android product policy layer.
- No built-in safety layer.

Best use:

- custom ROM/platform feature,
- research HAL,
- offline open-model assistant,
- native systems learning,
- device bring-up where full control matters.

## Option 2: MediaPipe LLM Inference API

MediaPipe LLM Inference is an Android app-level API for on-device LLM tasks.
Google's current Android guide says the MediaPipe LLM Inference API is in
maintenance-only mode and recommends migrating Android projects to LiteRT-LM.

Primary source:

- https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android

Why not for NeuralBind:

- It is aimed at application integration, not vendor HAL implementation.
- It does not teach Stable AIDL HAL internals.
- It abstracts away the exact native model/context/sampling path we want to
  study.
- Current Google guidance points Android developers toward LiteRT-LM.

When MediaPipe would make sense:

- quick app prototype,
- using supported MediaPipe task APIs,
- non-HAL app-level integration,
- when maintaining old MediaPipe LLM code.

Interview answer:

MediaPipe is a higher-level app pipeline. NeuralBind is a platform HAL. For this
project, MediaPipe would hide the exact Binder/native/service concepts we are
trying to master, and its Android LLM API is no longer the preferred forward path
according to current Google docs.

## Option 3: LiteRT-LM

LiteRT-LM is Google's production-ready orchestration layer for running LLMs with
LiteRT. The current docs describe Android/JVM Kotlin APIs, C++ APIs,
cross-platform support, GPU/NPU acceleration, multimodality, tool use, and broad
model support.

Primary sources:

- https://developers.google.com/edge/litert-lm/overview
- https://developers.google.com/edge/litert-lm/android

Strengths:

- Current Google AI Edge direction.
- Native Android Kotlin API.
- GPU and NPU acceleration path.
- Supports Gemma, Llama, Phi, Qwen, and more.
- More productized than raw llama.cpp.
- Good for production Android app development.

Weaknesses for this specific project:

- Higher-level than a HAL runtime.
- Integration path is more app/SDK-oriented.
- AOSP/vendor-process integration may be less direct than compiling C/C++ into a
  HAL.
- May require model conversion and runtime packaging decisions.
- Less useful for learning llama.cpp internals.

When LiteRT-LM would be better:

- shipping a user-facing Android app,
- needing Google-supported GPU/NPU acceleration,
- wanting less custom runtime code,
- wanting current Google AI Edge APIs.

NeuralBind conclusion:

LiteRT-LM is the strongest production alternative. If this project evolves from
HAL learning to product-grade Android app AI, evaluate LiteRT-LM seriously.
Still, llama.cpp remains better for the current "own the native HAL stack"
learning objective.

## Option 4: AICore / Gemini Nano / ML Kit GenAI

Gemini Nano runs through Android's AICore system service on supported devices.
Android's docs emphasize offline/private on-device experiences and note AICore
keeps the model up to date.

Primary source:

- https://developer.android.com/ai/gemini-nano

Strengths:

- System-managed model/runtime.
- No need to ship model files yourself.
- Privacy-friendly because requests run on-device.
- High-level APIs for supported tasks.
- Better product safety story than raw open-model runtime.

Weaknesses for NeuralBind:

- Model choice is limited to Gemini Nano / supported APIs.
- Device availability is constrained.
- Not suitable for custom GGUF model loading.
- Does not teach vendor HAL implementation.
- Not appropriate if you want full native runtime ownership.

When AICore is better:

- mainstream Android app feature,
- supported Pixel/OEM devices,
- high-level summarization/proofreading/rewriting/prompt use cases,
- no desire to manage model files.

NeuralBind conclusion:

AICore is the cleanest product API when its model and device constraints are
acceptable. NeuralBind uses llama.cpp because the project goal is a custom
platform HAL with open local models.

## Option 5: LiteRT / TensorFlow Lite

LiteRT is Google's successor/evolution path for TensorFlow Lite-style on-device
ML. Android docs position LiteRT for high-performance custom ML features, and the
LiteRT NPU docs describe a unified interface for NPU acceleration, including
Qualcomm AI Engine Direct support.

Primary sources:

- https://developer.android.com/ai/custom
- https://developers.google.com/edge/litert/next/npu
- https://developers.google.com/edge/litert/next/qualcomm

Strengths:

- Strong Android ecosystem support.
- Good for classic ML and many optimized models.
- GPU/NPU delegates.
- Better for vision/audio/small NLP models.
- Production-friendly.

Weaknesses for NeuralBind:

- LLM text generation requires more orchestration than a single inference call:
  tokenizer, decode loop, sampler, KV cache, streaming.
- Raw LiteRT is lower-level for GenAI than LiteRT-LM.
- Model conversion and delegate support can be work.
- The existing project already moved away from a TFLite prebuilt.

When LiteRT is better:

- image classification,
- object detection,
- speech/audio models,
- small transformer inference,
- NPU-accelerated perception workloads.

NeuralBind conclusion:

LiteRT is excellent for many on-device AI tasks, but LiteRT-LM is the more direct
Google path for LLMs. llama.cpp is more direct for GGUF/open-model native HAL
experimentation.

## Option 6: NNAPI

NNAPI was Android's C API for hardware-accelerated ML operations. Android's
current docs warn that NNAPI is deprecated and recommend migration paths.

Primary sources:

- https://developer.android.com/ndk/guides/neuralnetworks
- https://developer.android.com/ndk/guides/neuralnetworks/migration-guide

Why not:

- Deprecated in Android 15.
- Not the forward-looking API for a new Android 16/LineageOS 23 project.
- LLM generation still needs tokenizer/sampler/KV orchestration above the raw
  accelerator layer.

Interview answer:

NNAPI is historically important, but not the right new foundation for NeuralBind.
A modern project should use LiteRT/LiteRT-LM for Google AI Edge acceleration or a
runtime like llama.cpp/ExecuTorch/ONNX Runtime depending on model ecosystem.

## Option 7: ONNX Runtime / ONNX Runtime GenAI

ONNX Runtime is a cross-platform accelerator for ONNX models and can integrate
hardware-specific libraries. ONNX Runtime GenAI adds generative model loop
support for ONNX models.

Primary sources:

- https://onnxruntime.ai/docs/
- https://github.com/microsoft/onnxruntime-genai

Strengths:

- Strong cross-platform deployment story.
- Good when your model pipeline is already ONNX.
- Execution providers can target hardware acceleration.
- GenAI extensions handle LLM loop pieces.

Weaknesses for NeuralBind:

- Requires ONNX model path instead of GGUF.
- More model conversion/packaging work for common local LLM workflows.
- Android HAL integration is possible but not as straightforward as a small C++
  static library already vendored into the tree.

When ONNX Runtime is better:

- enterprise/cross-platform apps,
- existing ONNX model investment,
- models from PyTorch/TensorFlow exported to ONNX,
- desire for ONNX execution providers.

## Option 8: ExecuTorch

ExecuTorch is PyTorch's edge inference solution for devices from phones to
embedded systems, with portability, performance, and PyTorch workflow alignment
as core value propositions.

Primary source:

- https://docs.pytorch.org/executorch/stable/index.html

Strengths:

- Best fit for PyTorch-native teams.
- Supports mobile/embedded targets.
- Hardware acceleration direction includes CPU/GPU/NPU/DSP.
- Good when training/export lives in PyTorch.

Weaknesses for NeuralBind:

- More export/runtime integration work.
- Not as direct for loading arbitrary GGUF files.
- Less aligned with the current NeuralBind code and model files.

When ExecuTorch is better:

- PyTorch-owned model lifecycle,
- Meta/PyTorch ecosystem alignment,
- desire to export directly from PyTorch to edge runtime.

## Option 9: MLC LLM

MLC LLM is a machine-learning compiler and deployment engine for LLMs. Its
Android docs describe an NDK/CMake/TVM-based workflow and note physical devices
are needed for meaningful mobile GPU acceleration.

Primary source:

- https://llm.mlc.ai/docs/deploy/android.html

Strengths:

- Compiler-driven optimization.
- Strong GPU/mobile acceleration story.
- Good for native cross-platform LLM deployment.

Weaknesses for NeuralBind:

- More complex build and packaging workflow.
- TVM/compiler stack is a larger dependency.
- Less focused on Android HAL/AOSP teaching.

When MLC LLM is better:

- performance-focused LLM deployment,
- willingness to own compilation workflow,
- app-level native GPU acceleration.

## Comparison Table

| Approach | Best For | Why Not Primary For NeuralBind |
| --- | --- | --- |
| llama.cpp | Native open-model HAL, GGUF, full control | You own safety, threading, acceleration |
| MediaPipe LLM | Older app-level LLM demos | Maintenance-only for Android LLM path |
| LiteRT-LM | Production Android on-device LLM apps | Less direct HAL/native learning path |
| AICore/Gemini Nano | System-managed Gemini features | Limited model/device control |
| LiteRT/TFLite | Classic ML, NPU/GPU delegates | Raw LLM loop needs extra orchestration |
| NNAPI | Historical Android ML acceleration | Deprecated, not new-project foundation |
| ONNX Runtime | ONNX ecosystem deployment | Different model format and stack |
| ExecuTorch | PyTorch edge deployment | Export/runtime path, not GGUF-native |
| MLC LLM | Compiler-optimized mobile LLM | Larger compiler/runtime workflow |

## Why The HAL Approach Matters

If NeuralBind were only an app, a high-level SDK might be better.

But this project is teaching:

```text
AIDL contract
  -> Binder transactions
  -> native vendor service
  -> init rc
  -> VINTF
  -> SELinux
  -> native model runtime
  -> callback streaming
```

llama.cpp makes that visible. MediaPipe/LiteRT-LM/AICore intentionally hide much
of it to improve app developer productivity.

For interview prep, visibility is a feature.

## Recommended Long-Term Strategy

Short term:

- keep llama.cpp,
- fix lifecycle/threading,
- add structured errors,
- add cancellation,
- add sampler options,
- measure CPU performance.

Medium term:

- publish a real framework service,
- route app through framework API,
- tighten SELinux,
- add model directory policy,
- add perf counters and dumpsys.

Long term:

- evaluate LiteRT-LM for production app path,
- evaluate LiteRT Qualcomm/QNN for NPU acceleration if model format supports it,
- evaluate llama.cpp Vulkan/GPU backend if Android build supports it cleanly,
- keep AICore/Gemini Nano as a separate product feature path, not a replacement
  for open-model HAL learning.

## Interview Answer

We use llama.cpp because NeuralBind is a custom Android platform HAL, not just an
app feature. llama.cpp gives us a native C/C++ runtime, GGUF local model loading,
token-level streaming, and direct control over model/context/sampling lifecycle
inside a vendor service. MediaPipe and LiteRT-LM are higher-level app-oriented
paths; LiteRT-LM is probably the stronger production Android LLM SDK today, but
it hides much of the Binder/HAL/native runtime work this project is meant to
teach. AICore/Gemini Nano is excellent when you accept Google's model and device
constraints, while NeuralBind needs custom open models and platform ownership.

## Sources

- llama.cpp: https://github.com/ggml-org/llama.cpp
- MediaPipe LLM Inference Android: https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android
- LiteRT-LM overview: https://developers.google.com/edge/litert-lm/overview
- LiteRT-LM Android: https://developers.google.com/edge/litert-lm/android
- Gemini Nano / AICore: https://developer.android.com/ai/gemini-nano
- LiteRT Android: https://developer.android.com/ai/custom
- LiteRT NPU: https://developers.google.com/edge/litert/next/npu
- LiteRT Qualcomm/QNN: https://developers.google.com/edge/litert/next/qualcomm
- NNAPI: https://developer.android.com/ndk/guides/neuralnetworks
- ONNX Runtime: https://onnxruntime.ai/docs/
- ExecuTorch: https://docs.pytorch.org/executorch/stable/index.html
- MLC LLM Android: https://llm.mlc.ai/docs/deploy/android.html
