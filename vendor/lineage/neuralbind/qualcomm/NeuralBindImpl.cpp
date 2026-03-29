#define LOG_TAG "NeuralBindHAL"

#include "NeuralBindImpl.h"
#include <log/log.h>
#include <thread>

namespace aidl {
namespace android {
namespace hardware {
namespace neuralbind {

ndk::ScopedAStatus NeuralBindImpl::loadModel(const std::string& path) {
    ALOGD("loadModel called with path: %s", path.c_str());

    // 1. Initialize the llama.cpp backend
    llama_backend_init();

    // 2. Map the GGUF model file into RAM
    llama_model_params model_params = llama_model_default_params();
    mModel = llama_load_model_from_file(path.c_str(), model_params);
    
    if (!mModel) {
        ALOGE("FATAL ERROR - Failed to load GGUF model file at %s", path.c_str());
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }
    ALOGI("Successfully mapped GGUF model into memory.");

    // 3. Create the execution context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;
    mContext = llama_new_context_with_model(mModel, ctx_params);
    
    if (!mContext) {
        ALOGE("FATAL ERROR - Failed to build llama_context.");
        return ndk::ScopedAStatus::fromServiceSpecificError(1);
    }

    ALOGI("Llama context ready! The offline AI brain is online.");
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus NeuralBindImpl::submitPrompt(
    const std::string& prompt,
    const std::shared_ptr<IInferenceCallback>& callback) {
    
    ALOGD("submitPrompt called with prompt: %s", prompt.c_str());
    
    std::thread([this, prompt, callback]() {
        ALOGD("Worker Thread: Starting modern llama.cpp inference pipeline...");
        
        if (!mContext || !mModel) {
            ALOGE("FATAL: Model or Context is null! Call loadModel() first.");
            if (callback != nullptr) callback->onResponse("Error: Model not loaded.", true);
            return;
        }

        // Wipe short-term memory before tokenizing the new prompt
        // Destroy the old memory bucket and make a fresh 2048-token one
        if (mContext) {
            llama_free(mContext);
        }
        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = 2048;
        mContext = llama_new_context_with_model(mModel, ctx_params);
        
        // ========================================================================
        // STEP 1: Get the Vocab (NEW API REQUIREMENT)
        // ========================================================================
        const struct llama_vocab * vocab = llama_model_get_vocab(mModel);
        
        // ========================================================================
        // STEP 2: Tokenization
        // ========================================================================
        ALOGD("Tokenizing prompt...");
        const int max_tokens = prompt.length() + 16;
        std::vector<llama_token> tokens(max_tokens);
        
        int n_tokens = llama_tokenize(
            vocab, // <-- Changed from mModel to vocab
            prompt.c_str(),
            prompt.length(),
            tokens.data(),
            max_tokens,
            true,  // add_bos
            true   // special
        );
        
        if (n_tokens < 0) {
            ALOGE("Tokenization failed! Needed: %d", -n_tokens);
            if (callback != nullptr) callback->onResponse("Error: Tokenization failed.", true);
            return;
        }
        tokens.resize(n_tokens);
        ALOGI("Tokenized prompt into %d tokens.", n_tokens);
        
        // ========================================================================
        // STEP 3: Prompt Evaluation (NEW BATCH API)
        // ========================================================================
        llama_batch batch = llama_batch_init(n_tokens, 0, 1);
        batch.n_tokens = n_tokens;
        
        // Manually fill the batch arrays
        for (int i = 0; i < n_tokens; i++) {
            batch.token[i] = tokens[i];
            batch.pos[i] = i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = false;
        }
        // Mark the last token to generate logits (needed for sampling the next word)
        batch.logits[n_tokens - 1] = true;
        
        if (llama_decode(mContext, batch) != 0) {
            ALOGE("FATAL: llama_decode failed during prompt evaluation!");
            llama_batch_free(batch);
            if (callback != nullptr) callback->onResponse("Error: Prompt evaluation failed.", true);
            return;
        }
        
        // ========================================================================
        // STEP 4: Token Generation Loop (NEW SAMPLER API)
        // ========================================================================
        const int max_gen_tokens = 512;
        int n_generated = 0;
        
        llama_token eos_token = llama_vocab_eos(vocab); // <-- Changed from llama_token_eos
        
        // Initialize the new modular sampler
        struct llama_sampler * smpl = llama_sampler_init_greedy();
        
        while (n_generated < max_gen_tokens) {
            // Sample the next token
            llama_token new_token = llama_sampler_sample(smpl, mContext, -1);
            llama_sampler_accept(smpl, new_token);
            
            if (new_token == eos_token) {
                ALOGI("EOS token detected. Stopping generation.");
                break;
            }
            
            // ====================================================================
            // STEP 5: Detokenization
            // ====================================================================
            char piece_buf[256];
            int n_chars = llama_token_to_piece(
                vocab, // <-- Changed from mModel to vocab
                new_token,
                piece_buf,
                sizeof(piece_buf),
                0,
                true
            );
            
            if (n_chars > 0) {
                std::string piece(piece_buf, n_chars);
                if (callback != nullptr) {
                    callback->onResponse(piece, false); 
                }
            }
            
            // ====================================================================
            // STEP 6: Prepare next iteration (MANUAL BATCH ASSIGNMENT)
            // ====================================================================
            batch.n_tokens = 1;
            batch.token[0] = new_token;
            batch.pos[0] = n_tokens + n_generated;
            batch.n_seq_id[0] = 1;
            batch.seq_id[0][0] = 0;
            batch.logits[0] = true;
            
            if (llama_decode(mContext, batch) != 0) {
                ALOGE("FATAL: llama_decode failed during generation!");
                break;
            }
            
            n_generated++;
        }
        
        // ========================================================================
        // STEP 7: Cleanup
        // ========================================================================
        llama_sampler_free(smpl); // Free the new sampler
        llama_batch_free(batch);
        
        if (callback != nullptr) {
            callback->onResponse("[DONE]", true);
        }
        
    }).detach();
    
    return ndk::ScopedAStatus::ok();
}

}  // namespace neuralbind
}  // namespace hardware
}  // namespace android
}  // namespace aidl