# NeuralBind Study Guide Index

This directory is the organized study guide for the NeuralBind Android HAL stack.
The root file `NEURALBIND_HAL_STUDY_NOTES.md` keeps the first long-form review,
while these files split the project by Android layer.

## Files

1. `01-end-to-end-flow.md`
   - The full application to HAL to native inference flow.
   - Best first read before interviews.

2. `02-aidl-contract-and-build.md`
   - `INeuralBind.aidl`
   - `IInferenceCallback.aidl`
   - `hardware/interfaces/neuralbind/aidl/Android.bp`
   - Stable AIDL, VINTF stability, generated Java/NDK bindings.

3. `03-native-hal-service.md`
   - `service.cpp`
   - `NeuralBindImpl.h`
   - `NeuralBindImpl.cpp`
   - Native Binder service registration, llama.cpp model loading, inference loop,
     callback streaming, threading, memory, and performance risks.

4. `04-framework-service-and-test-client.md`
   - `NeuralBindService.java`
   - `SystemServer.java` integration
   - `neuralbind-test`
   - Framework boot connection, permission gate, direct test client behavior.

5. `05-app-client-and-storage.md`
   - `NeuralBindChat`
   - Compose UI, ViewModel, direct HAL connection, streaming callback, Room history.

6. `06-device-integration-selinux-vintf.md`
   - Device makefiles
   - init rc
   - VINTF manifest and framework compatibility matrix
   - SELinux domains, service contexts, file contexts, Binder permissions.

7. `07-review-risks-and-interview.md`
   - Highest-priority design review findings.
   - Common interview questions and strong answers.

## Suggested Study Order

Read in this order:

1. `01-end-to-end-flow.md`
2. `02-aidl-contract-and-build.md`
3. `03-native-hal-service.md`
4. `06-device-integration-selinux-vintf.md`
5. `04-framework-service-and-test-client.md`
6. `05-app-client-and-storage.md`
7. `07-review-risks-and-interview.md`

## One-Sentence Architecture

NeuralBind is a Stable AIDL vendor HAL exposed as
`android.hardware.neuralbind.INeuralBind/default`, implemented by a native vendor
process using llama.cpp, consumed directly by a privileged platform app and test
client, and partially wrapped by a `system_server` service.
