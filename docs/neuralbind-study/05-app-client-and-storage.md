# App Client And Storage

## Files Covered

- `packages/apps/NeuralBindChat/Android.bp`
- `packages/apps/NeuralBindChat/AndroidManifest.xml`
- `packages/apps/NeuralBindChat/src/com/android/neuralbind/chat/ChatViewModel.kt`
- `packages/apps/NeuralBindChat/src/com/android/neuralbind/chat/MainActivity.kt`
- `packages/apps/NeuralBindChat/src/com/android/neuralbind/chat/data/*.kt`

## Purpose

`NeuralBindChat` is the user-facing app. It provides a Compose chat UI, connects
to the HAL, loads a model, submits prompts, streams responses, and stores chat
history using Room.

## Build And Privilege

The app is built as:

```bp
android_app {
    name: "NeuralBindChat",
    platform_apis: true,
    certificate: "platform",
    privileged: true,
    static_libs: [
        ...
        "android.hardware.neuralbind-V1-java",
    ],
}
```

Implication:

This is a privileged platform-signed app using platform APIs and directly linking
the generated HAL Java AIDL library.

Security point:

This is why SELinux policy grants `platform_app` access to the NeuralBind HAL.
For a cleaner architecture, the app should call a framework service instead.

## Manifest

The app declares:

```xml
<uses-permission android:name="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

It does not declare `android.permission.USE_NEURALBIND` in the visible manifest.
Because the app directly calls the HAL, it bypasses the permission check inside
`NeuralBindService`.

## `ChatViewModel`

Responsibilities:

- hold UI message state,
- connect to `INeuralBind/default`,
- load selected model,
- create callback for streaming responses,
- build sliding-window prompt context,
- persist messages using Room.

Service connection:

```kotlin
val binder = ServiceManager.waitForDeclaredService(SERVICE_NAME)
neuralBindService = INeuralBind.Stub.asInterface(binder)
```

Default model load:

```kotlin
val modelPath = "/data/vendor/${selectedModel.value.fileName}"
neuralBindService?.loadModel(modelPath)
```

Model switching:

The model list maps display names to files:

- `gemma.gguf`
- `deepseek.gguf`
- `phi4.gguf`

Switching calls `loadModel` again with the selected path.

## Streaming Callback

The app creates:

```kotlin
object : IInferenceCallback.Stub() {
    override fun onResponse(content: String, isFinished: Boolean) { ... }
}
```

The callback:

- appends chunks into a `StringBuilder`,
- strips control tokens,
- updates the streaming UI message on the main thread,
- saves the final AI response once.

Important threading point:

AIDL callbacks arrive on Binder threads, not the UI thread. The code correctly
uses `viewModelScope.launch(Dispatchers.Main)` before updating Compose state.

## Prompt Context

`buildSlidingWindowContext` reads the last 2 messages and creates a Gemma-style
prompt:

```text
<bos>
<|turn|>system
...
<|turn|>user
...
<|turn|>model
```

This is application-level prompt formatting. The HAL does not know chat history
or roles; it only receives a final prompt string.

## Room Storage

Files:

- `ChatDatabase.kt`
- `ChatMessageDao.kt`
- `ChatMessageEntity.kt`

Schema:

```kotlin
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
```

DAO operations:

- get all messages ordered ascending,
- get last N messages ordered descending,
- insert message,
- clear all messages.

## UI Layer

`MainActivity.kt` uses Compose:

- `Scaffold`
- `TopAppBar`
- model dropdown,
- clear history menu,
- `LazyColumn` for chat messages,
- message input field,
- streaming progress indicator.

The UI is mostly independent from Binder. The ViewModel owns Binder and database
work.

## Design Review

Strengths:

- UI state is isolated in ViewModel.
- Binder callbacks switch to main thread before UI mutation.
- Room calls are mostly on IO dispatcher.
- Streaming UI is simple and effective.
- Sliding context keeps prompts bounded.

Weaknesses:

- App directly accesses vendor HAL.
- No framework permission path is exercised.
- `getInterfaceHash()` returns an empty string in callback; generated stable
  AIDL expectations may deserve closer validation.
- `isModelLoaded` is a plain Boolean changed from background coroutine.
- No cancellation if user sends another prompt.
- Multiple prompt sends can overlap and trigger HAL races.
- Model paths are hardcoded to `/data/vendor`.
- Chat history stores all AI text, which may be sensitive.

## Debugging

App logs:

```bash
adb logcat | grep ChatViewModel
```

Launch app:

```bash
adb shell monkey -p com.android.neuralbind.chat 1
```

Check database:

```bash
adb shell run-as com.android.neuralbind.chat ls databases
```

For privileged platform apps, `run-as` may not work depending on build flags.
Use root on engineering builds if needed.

## Interview Questions

Easy:

- Why does the callback switch to `Dispatchers.Main`?
- What does Room provide?
- Why use a ViewModel?

Medium:

- Why is direct HAL access from an app discouraged?
- What happens if the HAL sends callbacks after the ViewModel is cleared?
- How would you cancel streaming?

Hard:

- Move this app to a proper framework manager API.
- Design secure model selection.
- Prevent concurrent prompts from corrupting HAL state.
