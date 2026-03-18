#pragma once

#include <aidl/android/hardware/neuralbind/BnNeuralBind.h>
#include <aidl/android/hardware/neuralbind/IInferenceCallback.h>

// TensorFlow Lite headers
#include "tensorflow/lite/interpreter.h"
#include "tensorflow/lite/model_builder.h"
#include "tensorflow/lite/kernels/register.h"

#include <memory>
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
    // TFLite Engine Components
    std::unique_ptr<tflite::FlatBufferModel> mModel;
    std::unique_ptr<tflite::Interpreter> mInterpreter;
};

}  // namespace neuralbind
}  // namespace hardware
}  // namespace android
}  // namespace aidl