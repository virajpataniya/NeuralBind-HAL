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

    // 1. Map the model file into memory
    mModel = tflite::FlatBufferModel::BuildFromFile(path.c_str());
    if (!mModel) {
        ALOGE("FATAL ERROR - Failed to load model file at %s", path.c_str());
        return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    }
    ALOGI("Successfully mapped FlatBuffer model into memory.");

    // 2. Build the Interpreter
    tflite::ops::builtin::BuiltinOpResolver resolver;
    tflite::InterpreterBuilder builder(*mModel, resolver);
    if (builder(&mInterpreter) != kTfLiteOk || !mInterpreter) {
        ALOGE("FATAL ERROR - Failed to build TFLite interpreter.");
        return ndk::ScopedAStatus::fromServiceSpecificError(1);
    }

    // 3. Allocate RAM Tensors for the Model
    if (mInterpreter->AllocateTensors() != kTfLiteOk) {
        ALOGE("FATAL ERROR - Failed to allocate tensors.");
        return ndk::ScopedAStatus::fromServiceSpecificError(2);
    }

    ALOGI("Interpreter ready and tensors allocated! The AI brain is online.");
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus NeuralBindImpl::submitPrompt(
    const std::string& prompt,
    const std::shared_ptr<IInferenceCallback>& callback) {
    
    ALOGD("submitPrompt called with prompt: %s", prompt.c_str());
    
    std::thread([this, prompt, callback]() {
        ALOGD("Worker Thread: Processing prompt...");
        
        if (!mInterpreter) {
             ALOGE("Cannot run inference: Interpreter is null! Did you call loadModel first?");
             if (callback != nullptr) callback->onResponse("Error: Model not loaded.", true);
             return;
        }
        
        ALOGD("Model is locked and loaded. Awaiting inference loop!");
        
        if (callback != nullptr) {
            callback->onResponse("ACK: Model loaded and ready for inference!", true);
        }
    }).detach();
    
    return ndk::ScopedAStatus::ok();
}

}  // namespace neuralbind
}  // namespace hardware
}  // namespace android
}  // namespace aidl