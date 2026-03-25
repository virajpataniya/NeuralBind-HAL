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
        ALOGD("Worker Thread: Processing prompt...");
        
        // Check our new llama.cpp variables instead of TFLite
        if (!mContext || !mModel) {
             ALOGE("Cannot run inference: Context is null! Did you call loadModel first?");
             if (callback != nullptr) callback->onResponse("Error: Model not loaded.", true);
             return;
        }
        
        ALOGD("Model is locked and loaded. Awaiting inference loop!");
        
        if (callback != nullptr) {
            callback->onResponse("ACK: Llama engine loaded and ready for inference!", true);
        }
    }).detach();
    
    return ndk::ScopedAStatus::ok();
}

}  // namespace neuralbind
}  // namespace hardware
}  // namespace android
}  // namespace aidl