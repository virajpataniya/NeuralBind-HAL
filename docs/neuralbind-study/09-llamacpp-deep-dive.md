# llama.cpp Deep Dive For NeuralBind

## Scope

This file explains the vendored `llamacpp` tree as it matters to NeuralBind.

It does not attempt to explain every upstream tool, benchmark, CI script, or
example app. Instead, it focuses on the runtime path used by:

```text
NeuralBindImpl.cpp
  -> llama_backend_init
  -> llama_model_load_from_file / llama_load_model_from_file
  -> llama_init_from_model / llama_new_context_with_model
  -> llama_model_get_vocab
  -> llama_tokenize
  -> llama_batch_init
  -> llama_decode
  -> llama_sampler_sample
  -> llama_token_to_piece
  -> llama_free / llama_model_free
```

## What llama.cpp Is

`llama.cpp` is a C/C++ inference engine for large language models. Its upstream
README describes its goal as local/cloud LLM inference with minimal setup and
broad hardware support. It emphasizes a mostly plain C/C++ implementation,
quantized model support, CPU SIMD paths, and optional GPU backends.

Primary source:

- https://github.com/ggml-org/llama.cpp

In NeuralBind, llama.cpp is not used as a CLI or server. It is embedded as a
native static library inside an Android vendor HAL process.

## Why llama.cpp Fits NeuralBind

NeuralBind needs:

- native C/C++ inference inside `/vendor`,
- no app-level cloud dependency,
- GGUF model loading from local storage,
- streaming token generation,
- direct control over model/context/token lifecycle,
- Android HAL compatibility,
- buildability through Soong.

llama.cpp fits because it exposes a C ABI-ish API in `include/llama.h`, which is
much easier to call from a native HAL than a high-level app SDK.

## Source Tree Map

Important directories:

- `include/`
  - Public API headers.
  - `llama.h` is the main API NeuralBind calls.
  - `llama-cpp.h` has C++ RAII helpers.

- `src/`
  - Core llama.cpp implementation.
  - Model loading, model metadata, context execution, sampling, tokenizer,
    grammar, KV cache, memory handling.

- `src/models/`
  - Model architecture implementations.
  - This is where support for different transformer families lives.

- `ggml/`
  - Tensor library and backend abstraction.
  - Responsible for low-level tensor storage, quantization, CPU kernels,
    scheduling, and optional backend support.

- `gguf-py/`, conversion scripts
  - Python tooling for converting Hugging Face or older formats into GGUF.

- `tools/`
  - CLI, server, quantize, benchmark, tokenize, perplexity, and other developer
    tools.

- `examples/`
  - Example integrations, including Android examples.

- `docs/`
  - Upstream build, backend, multimodal, ops, and development docs.

## Android Build Integration

NeuralBind's vendored `llamacpp/Android.bp` creates two static libraries:

```bp
cc_library_static {
    name: "libggml_c",
    vendor: true,
    srcs: [
        "ggml/src/ggml.c",
        "ggml/src/ggml-alloc.c",
        "ggml/src/ggml-quants.c",
        "ggml/src/ggml-cpu/**/*.c",
    ],
}
```

and:

```bp
cc_library_static {
    name: "libllamacpp_static",
    vendor: true,
    srcs: [
        "src/*.cpp",
        "src/models/*.cpp",
        "ggml/src/ggml.cpp",
        "ggml/src/ggml-backend.cpp",
        "ggml/src/gguf.cpp",
        "ggml/src/ggml-cpu/**/*.cpp",
    ],
    whole_static_libs: ["libggml_c"],
}
```

NeuralBind's HAL then links:

```bp
static_libs: [
    "libllamacpp_static",
    "libggml_c",
]
```

Design meaning:

- llama.cpp is compiled into the vendor HAL binary, not loaded as a separate
  shared library.
- The build currently targets CPU execution through `-DGGML_USE_CPU`.
- Incompatible CPU architecture sources are excluded for Android arm64.
- This is simple and portable, but it leaves GPU/NPU acceleration for future
  work.

## Public API Concepts

### `llama_model`

`llama_model` represents loaded model weights and metadata.

In `include/llama.h`, it is forward-declared:

```cpp
struct llama_model;
```

NeuralBind stores it as:

```cpp
llama_model* mModel = nullptr;
```

Interview explanation:

The model object is heavy and should be loaded once when possible. It owns or
references the model tensors and metadata. Multiple contexts can be created from
one model when you want multiple independent sessions.

### `llama_context`

`llama_context` represents an execution state for a model.

NeuralBind stores it as:

```cpp
llama_context* mContext = nullptr;
```

The context includes runtime memory, sequence state, and KV cache state. In an
LLM, the KV cache is critical: it stores attention keys/values for previous
tokens so generation does not recompute the full prefix every time.

Important design point:

If you want multiple independent chats, you usually want separate sequence state
or separate contexts. Sharing one mutable `mContext` across detached threads is
unsafe.

### `llama_vocab`

`llama_vocab` represents tokenizer vocabulary and special-token behavior.

NeuralBind calls:

```cpp
const struct llama_vocab* vocab = llama_model_get_vocab(mModel);
```

The tokenizer is model-specific. Gemma, Llama, Qwen, Phi, and other families can
have different tokenization and special-token rules.

### `llama_token`

`llama_token` is a numeric token ID:

```cpp
typedef int32_t llama_token;
```

Text is converted to tokens before decode. Tokens are converted back into text
pieces after sampling.

## Model Load Flow

Current NeuralBind code:

```cpp
llama_backend_init();
llama_model_params model_params = llama_model_default_params();
mModel = llama_load_model_from_file(path.c_str(), model_params);
llama_context_params ctx_params = llama_context_default_params();
ctx_params.n_ctx = 2048;
mContext = llama_new_context_with_model(mModel, ctx_params);
```

Modern API note:

The local `llama.h` marks these as deprecated:

- `llama_load_model_from_file`
- `llama_new_context_with_model`

The modern names are:

- `llama_model_load_from_file`
- `llama_init_from_model`

Recommended NeuralBind migration:

```cpp
mModel = llama_model_load_from_file(path.c_str(), model_params);
mContext = llama_init_from_model(mModel, ctx_params);
```

## Model Params

`llama_model_params` includes:

- devices for offload,
- tensor buffer overrides,
- GPU layer count,
- split mode,
- progress callback,
- metadata overrides,
- `use_mmap`,
- `use_mlock`,
- tensor checking,
- no-allocation modes.

For Android HAL:

- `use_mmap` is important because large GGUF files should not be eagerly copied
  into anonymous heap if avoidable.
- `use_mlock` can improve latency but risks memory pressure and low-memory
  killer behavior.
- `check_tensors` helps catch corrupted files but adds load overhead.
- progress callback could stream model-load progress to framework/app in a
  future API.

## Context Params

`llama_context_params` includes:

- `n_ctx`: context window,
- `n_batch`: logical batch size,
- `n_ubatch`: physical batch size,
- `n_seq_max`: max number of sequences,
- `n_threads`,
- `n_threads_batch`,
- RoPE scaling controls,
- flash attention settings,
- K/V cache types,
- abort callback,
- embeddings mode,
- offload flags.

NeuralBind sets only:

```cpp
ctx_params.n_ctx = 2048;
```

That is a reasonable bring-up default, but interviewers may ask why:

- 2048 caps memory and latency,
- longer context requires more KV cache memory,
- small phones need bounded memory,
- model quality may degrade if prompt exceeds available context.

## Tokenization

NeuralBind:

```cpp
const int max_tokens = prompt.length() + 16;
std::vector<llama_token> tokens(max_tokens);
int n_tokens = llama_tokenize(
    vocab,
    prompt.c_str(),
    prompt.length(),
    tokens.data(),
    max_tokens,
    true,
    true
);
```

Meaning:

- input text becomes token IDs,
- `add_special = true` allows BOS/EOS or model-defined special tokens,
- `parse_special = true` treats known special/control tokens as special tokens
  instead of ordinary text.

Risk:

`prompt.length() + 16` is a heuristic. Token count is often less than character
count for English, but not guaranteed across all scripts and tokenizers. A robust
implementation should call once with a too-small buffer and use the negative
return value to allocate the required size.

## Batches

`llama_batch` is the input structure for `llama_decode`.

It includes:

- `token`: token IDs,
- `pos`: positions,
- `seq_id`: which sequence each token belongs to,
- `logits`: whether logits should be output for each token.

NeuralBind prompt evaluation:

```cpp
llama_batch batch = llama_batch_init(n_tokens, 0, 1);
batch.n_tokens = n_tokens;
for (int i = 0; i < n_tokens; i++) {
    batch.token[i] = tokens[i];
    batch.pos[i] = i;
    batch.n_seq_id[i] = 1;
    batch.seq_id[i][0] = 0;
    batch.logits[i] = false;
}
batch.logits[n_tokens - 1] = true;
llama_decode(mContext, batch);
```

Why only last token has logits:

For next-token generation, you only need logits after the final prompt token.
Computing/storing logits for every prompt token wastes memory and time.

Generation batch:

```cpp
batch.n_tokens = 1;
batch.token[0] = new_token;
batch.pos[0] = n_tokens + n_generated;
batch.n_seq_id[0] = 1;
batch.seq_id[0][0] = 0;
batch.logits[0] = true;
llama_decode(mContext, batch);
```

Each generated token is fed back into the model so the next token can be sampled.

## Decode

`llama_decode(ctx, batch)` runs the model forward pass for the supplied tokens.

Conceptually:

```text
tokens + positions + sequence IDs
  -> embedding lookup
  -> transformer layers
  -> KV cache update
  -> logits for requested positions
```

In the local source, `llama_decode` delegates to:

```cpp
ctx->decode(batch)
```

Return values matter:

- `0`: success,
- `1`: no KV slot for batch,
- `2`: aborted,
- negative: invalid input or fatal error.

Current NeuralBind treats any nonzero as failure. That is acceptable for a first
HAL, but production code should distinguish recoverable "no KV slot" from fatal
errors.

## Sampling

NeuralBind uses:

```cpp
struct llama_sampler* smpl = llama_sampler_init_greedy();
llama_token new_token = llama_sampler_sample(smpl, mContext, -1);
llama_sampler_accept(smpl, new_token);
```

Greedy sampling chooses the highest-probability token. It is deterministic and
simple, but often less creative and can produce repetitive responses.

llama.cpp supports richer samplers, including:

- top-k,
- top-p,
- min-p,
- temperature,
- penalties,
- mirostat,
- grammar-constrained sampling.

Recommended NeuralBind improvement:

Expose inference parameters through AIDL:

```aidl
parcelable InferenceOptions {
    int maxTokens;
    float temperature;
    int topK;
    float topP;
    float repeatPenalty;
}
```

Then build a sampler chain instead of always greedy.

## Detokenization

NeuralBind:

```cpp
char piece_buf[256];
int n_chars = llama_token_to_piece(
    vocab,
    new_token,
    piece_buf,
    sizeof(piece_buf),
    0,
    true
);
```

This converts a token ID back to bytes/text.

Risk:

Some tokens or byte fallback behavior can be tricky with UTF-8 boundaries.
Streaming token-by-token can send partial multi-byte sequences or odd spacing.
The app currently cleans control tokens at the accumulated string level, which is
reasonable, but HAL-side chunking should become more robust.

## GGUF

GGUF is the common model file format used by llama.cpp.

Why it matters:

- stores tensors,
- stores metadata,
- stores tokenizer information,
- supports quantized weights,
- avoids depending on Python/PyTorch at runtime,
- is friendly to local file loading.

In NeuralBind, model files are expected under:

```text
/data/vendor/gemma.gguf
/data/vendor/deepseek.gguf
/data/vendor/phi4.gguf
```

Security implication:

GGUF files are complex untrusted inputs if supplied by users. The HAL should
validate model path policy and ideally load only from a controlled directory.

## ggml

`ggml` is the tensor engine underneath llama.cpp.

In this project:

- `libggml_c` builds low-level C files,
- `libllamacpp_static` builds C++ runtime files and whole-links `libggml_c`,
- `-DGGML_USE_CPU` selects CPU backend behavior.

Key ideas:

- tensors are represented in ggml structures,
- computation is expressed as graphs,
- graph execution happens on available backend implementation,
- quantization formats reduce model memory and bandwidth,
- CPU kernels use architecture-specific optimized code where available.

For Android arm64, this matters because CPU NEON support and memory bandwidth
often dominate performance.

## Performance Model

LLM latency has two major phases:

1. Prefill:
   - processes the prompt,
   - parallel over many input tokens,
   - affected by prompt length and batch settings.

2. Decode:
   - generates one token at a time,
   - latency-sensitive,
   - affected by model size, quantization, thread count, KV cache, and memory
     bandwidth.

NeuralBind current behavior:

- reloads context for each prompt,
- prefill decodes the full prompt,
- generates up to 512 tokens,
- streams every piece through Binder callback.

Possible optimizations:

- keep session context when continuing a chat,
- use a bounded worker queue,
- tune `n_threads` and `n_threads_batch`,
- batch callback chunks to reduce Binder overhead,
- use smaller quantized models,
- experiment with GPU/Vulkan backend later,
- measure before changing.

## Memory Model

Major memory consumers:

- model weights,
- KV cache,
- temporary compute buffers,
- tokenizer metadata,
- prompt token arrays,
- batch arrays.

Context size affects KV cache heavily. Increasing `n_ctx` from 2048 to 8192 is
not free; it can significantly increase memory pressure.

Current leak risks in NeuralBind:

- old `mModel` not freed when switching model,
- final `mModel` not freed in destructor,
- final `mContext` not freed in destructor,
- detached thread captures `this`.

Recommended RAII shape:

```cpp
struct LlamaModelDeleter {
    void operator()(llama_model* model) const { llama_model_free(model); }
};

struct LlamaContextDeleter {
    void operator()(llama_context* ctx) const { llama_free(ctx); }
};

std::unique_ptr<llama_model, LlamaModelDeleter> mModel;
std::unique_ptr<llama_context, LlamaContextDeleter> mContext;
```

The local `include/llama-cpp.h` already provides similar C++ deleters.

## Thread Safety

llama.cpp contexts should be treated as mutable execution state.

Current NeuralBind risk:

```cpp
std::thread([this, prompt, callback]() { ... }).detach();
```

Inside that worker:

```cpp
llama_free(mContext);
mContext = llama_new_context_with_model(mModel, ctx_params);
```

If two prompts run at once, one thread can free the context while another uses
it. This is the most important native correctness bug.

Safer design:

```text
HAL Binder thread
  -> validates request
  -> pushes request into single worker queue
  -> returns

single inference worker
  -> owns model/context mutation
  -> streams callbacks
```

If parallelism is required, use one context per active request and put a strict
memory limit around it.

## How NeuralBind Uses llama.cpp Today

Current stack:

```text
loadModel(path)
  -> llama_backend_init
  -> llama_load_model_from_file
  -> llama_new_context_with_model

submitPrompt(prompt, callback)
  -> detach worker
  -> recreate context
  -> tokenize prompt
  -> build prompt batch
  -> llama_decode prompt
  -> greedy sample
  -> detokenize
  -> callback pieces
```

This is good for proving:

- HAL registration,
- local model loading,
- Binder callback streaming,
- native LLM execution.

It is not yet production-grade because concurrency, lifecycle, cancellation, and
structured errors are not complete.

## Debugging llama.cpp In NeuralBind

Logs:

```bash
adb logcat | grep NeuralBindHAL
```

Native crash:

```bash
adb logcat -b crash
adb shell ls -l /data/tombstones
adb shell debuggerd -b $(adb shell pidof android.hardware.neuralbind-service)
```

Memory:

```bash
adb shell dumpsys meminfo android.hardware.neuralbind-service
adb shell cat /proc/$(adb shell pidof android.hardware.neuralbind-service)/smaps_rollup
```

Performance:

```bash
adb shell simpleperf record -p $(adb shell pidof android.hardware.neuralbind-service) --duration 20
adb shell simpleperf report
```

Thermals:

```bash
adb shell dumpsys thermalservice
adb shell cat /sys/class/thermal/thermal_zone*/temp
```

## Interview Questions

Easy:

- What is GGUF?
- What is the difference between a model and a context?
- Why do we tokenize before inference?
- What does `llama_decode` do?

Medium:

- Why should we not share one `llama_context` across concurrent threads?
- Why does context length increase memory usage?
- What is the difference between prefill and decode?
- Why is greedy sampling deterministic?

Hard:

- Design multi-session chat support using llama.cpp.
- Add cancellation using `abort_callback`.
- Add sampler parameters through Stable AIDL without breaking V1.
- Reduce Binder overhead while preserving streaming UX.
- Explain how quantization changes memory bandwidth and quality.

## Best Interview Answer

NeuralBind embeds llama.cpp because it gives the HAL native ownership of the LLM
runtime. The model is loaded from GGUF into a `llama_model`, each inference uses a
`llama_context` containing execution state and KV cache, prompts are converted to
tokens, `llama_decode` advances the transformer state, a sampler chooses the next
token from logits, and detokenization converts tokens back to streamed text. The
main production work is making model/context lifetime, concurrency, cancellation,
and error handling safe across Binder boundaries.

## Sources

- llama.cpp upstream README: https://github.com/ggml-org/llama.cpp
- Local public API: `vendor/lineage/neuralbind/qualcomm/llamacpp/include/llama.h`
- Local Android build: `vendor/lineage/neuralbind/qualcomm/llamacpp/Android.bp`
- Local NeuralBind integration: `vendor/lineage/neuralbind/qualcomm/NeuralBindImpl.cpp`
