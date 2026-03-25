#pragma once

#include <aidl/android/hardware/neuralbind/BnNeuralBind.h>
#include <aidl/android/hardware/neuralbind/IInferenceCallback.h>

// The New Brain: llama.cpp
#include "llama.h"

#include <string>

namespace aidl {
namespace android {
namespace hardware {
namespace neuralbind {

class NeuralBindImpl : public BnNeuralBind {
public:
    NeuralBindImpl() = default;
    ~NeuralBindImpl() override = default;

    ndk::ScopedAStatus loadModel(const std::string& path) override;
    
    ndk::ScopedAStatus submitPrompt(
        const std::string& prompt,
        const std::shared_ptr<IInferenceCallback>& callback) override;

private:
    // llama.cpp Engine Components (Replacing TFLite)
    llama_model* mModel = nullptr;
    llama_context* mContext = nullptr;
};

}  // namespace neuralbind
}  // namespace hardware
}  // namespace android
}  // namespace aidl