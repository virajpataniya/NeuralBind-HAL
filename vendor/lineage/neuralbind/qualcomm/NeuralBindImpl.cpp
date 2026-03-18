#define LOG_TAG "NeuralBindHAL"

#include "NeuralBindImpl.h"
#include <log/log.h>
#include <thread> // Required for async worker thread

namespace aidl {
namespace android {
namespace hardware {
namespace neuralbind {

ndk::ScopedAStatus NeuralBindImpl::loadModel(const std::string& path) {
    ALOGD("loadModel called with path: %s", path.c_str());
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus NeuralBindImpl::submitPrompt(
    const std::string& prompt,
    const std::shared_ptr<IInferenceCallback>& callback) {
    
    ALOGD("submitPrompt called with prompt: %s. Spinning up worker thread.", prompt.c_str());
    
    // Detach a worker thread to prevent Binder thread starvation
    std::thread([prompt, callback]() {
        ALOGD("Worker Thread: Processing prompt...");
        
        // (In the future, QNN/TFLite inference happens here)
        
        if (callback != nullptr) {
            callback->onResponse("Hello from async C++ worker!", true);
        }
    }).detach();
    
    return ndk::ScopedAStatus::ok();
}

}  // namespace neuralbind
}  // namespace hardware
}  // namespace android
}  // namespace aidl