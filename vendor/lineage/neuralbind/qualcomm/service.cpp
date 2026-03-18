#define LOG_TAG "NeuralBindHAL"

#include "NeuralBindImpl.h"
#include <log/log.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>

using aidl::android::hardware::neuralbind::NeuralBindImpl;

int main() {
    ALOGI("Service starting...");

    ABinderProcess_setThreadPoolMaxThreadCount(4);

    std::shared_ptr<NeuralBindImpl> service = ndk::SharedRefBase::make<NeuralBindImpl>();

    const std::string instance = std::string() + NeuralBindImpl::descriptor + "/default";
    binder_status_t status = AServiceManager_addService(service->asBinder().get(), instance.c_str());

    if (status != STATUS_OK) {
        ALOGE("Failed to register service: %d", status);
        return 1;
    }

    ALOGI("Service registered successfully as: %s", instance.c_str());

    ABinderProcess_joinThreadPool();

    return 0;
}