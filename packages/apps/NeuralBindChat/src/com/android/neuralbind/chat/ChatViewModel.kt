/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.neuralbind.chat

import android.app.Application
import android.hardware.neuralbind.IInferenceCallback
import android.hardware.neuralbind.INeuralBind
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.neuralbind.chat.data.ChatDatabase
import com.android.neuralbind.chat.data.ChatMessageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ChatViewModel"
private const val SERVICE_NAME = "android.hardware.neuralbind.INeuralBind/default"
private const val SLIDING_WINDOW_SIZE = 2

// Add it right here, at the top level of the file
private val controlTokens = listOf(
    "<end_of_turn>", "[DONE]", "<eos>", "<bos>", 
    "<|eot_id|>", "<|im_end|>", "<|turn|>"
)

enum class MessageRole {
    USER,
    AI
}

data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val isStreaming: Boolean = false
)

data class ModelInfo(
    val displayName: String,
    val fileName: String
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    
    val messages = mutableStateListOf<ChatMessage>()
    private var neuralBindService: INeuralBind? = null
    private var isModelLoaded = false
    
    private val database = ChatDatabase.getDatabase(application)
    private val chatDao = database.chatMessageDao()
    
    // Available models
    val availableModels = listOf(
        ModelInfo("Gemma-4-E2B-it", "gemma.gguf"),
        ModelInfo("DeepSeek-R1", "deepseek.gguf"),
        ModelInfo("Phi-4", "phi4.gguf")
    )
    
    // Currently selected model
    val selectedModel = mutableStateOf(availableModels[0])
    
    init {
        loadChatHistory()
        initializeService()
    }
    
    private fun loadChatHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val history = chatDao.getAllMessages()
                withContext(Dispatchers.Main) {
                    messages.clear()
                    history.forEach { entity ->
                        messages.add(
                            ChatMessage(
                                role = if (entity.isFromUser) MessageRole.USER else MessageRole.AI,
                                content = entity.text,
                                isStreaming = false
                            )
                        )
                    }
                }
                Log.d(TAG, "Loaded ${history.size} messages from database")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load chat history", e)
            }
        }
    }
    
    fun selectModel(model: ModelInfo) {
        selectedModel.value = model
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val modelPath = "/data/vendor/${model.fileName}"
                neuralBindService?.loadModel(modelPath)
                isModelLoaded = true
                Log.d(TAG, "Switched to model: ${model.displayName} at $modelPath")
                
                withContext(Dispatchers.Main) {
                    messages.add(
                        ChatMessage(
                            role = MessageRole.AI,
                            content = "Switched to ${model.displayName}. Ready!"
                        )
                    )
                }
                
                // Save system message to database
                chatDao.insertMessage(
                    ChatMessageEntity(
                        text = "Switched to ${model.displayName}. Ready!",
                        isFromUser = false
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch model", e)
                withContext(Dispatchers.Main) {
                    messages.add(
                        ChatMessage(
                            role = MessageRole.AI,
                            content = "Error switching model: ${e.message}"
                        )
                    )
                }
            }
        }
    }
    
    private fun initializeService() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Waiting for NeuralBind service...")
                val binder: IBinder = ServiceManager.waitForDeclaredService(SERVICE_NAME)
                    ?: throw IllegalStateException("Failed to get service binder")
                
                neuralBindService = INeuralBind.Stub.asInterface(binder)
                Log.d(TAG, "NeuralBind service connected")
                
                // Load the default model
                val modelPath = "/data/vendor/${selectedModel.value.fileName}"
                neuralBindService?.loadModel(modelPath)
                isModelLoaded = true
                Log.d(TAG, "Model loaded from $modelPath")
                
                withContext(Dispatchers.Main) {
                    // Only add welcome message if no history exists
                    if (messages.isEmpty()) {
                        messages.add(
                            ChatMessage(
                                role = MessageRole.AI,
                                content = "Model loaded. How can I help you today?"
                            )
                        )
                        // Save to database
                        chatDao.insertMessage(
                            ChatMessageEntity(
                                text = "Model loaded. How can I help you today?",
                                isFromUser = false
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize service", e)
                withContext(Dispatchers.Main) {
                    messages.add(
                        ChatMessage(
                            role = MessageRole.AI,
                            content = "Error: Failed to connect to NeuralBind service. ${e.message}"
                        )
                    )
                }
            }
        }
    }
    
    fun sendMessage(prompt: String) {
        if (prompt.isBlank()) return
        
        viewModelScope.launch {
            // Add user message to UI
            withContext(Dispatchers.Main) {
                messages.add(ChatMessage(role = MessageRole.USER, content = prompt))
            }
            
            // Save user message to database
            withContext(Dispatchers.IO) {
                chatDao.insertMessage(
                    ChatMessageEntity(
                        text = prompt,
                        isFromUser = true
                    )
                )
            }
            
            if (!isModelLoaded || neuralBindService == null) {
                withContext(Dispatchers.Main) {
                    messages.add(
                        ChatMessage(
                            role = MessageRole.AI,
                            content = "Error: Service not ready. Please wait for initialization."
                        )
                    )
                }
                return@launch
            }
            
            // Add placeholder AI message for streaming
            val aiMessageIndex = withContext(Dispatchers.Main) {
                messages.add(
                    ChatMessage(
                        role = MessageRole.AI,
                        content = "",
                        isStreaming = true
                    )
                )
                messages.size - 1
            }
            
            try {
                val contextPrompt = withContext(Dispatchers.IO) {
                    buildSlidingWindowContext(prompt)
                }
                
val callback = object : IInferenceCallback.Stub() {
    private val aiResponseBuilder = StringBuilder()
    private var isCompleted = false // Guard to prevent multiple DB inserts

    override fun onResponse(content: String, isFinished: Boolean) {
        if (isCompleted) return

        // Switch to Main thread to update the UI StateFlow safely
        viewModelScope.launch(Dispatchers.Main) {
            if (aiMessageIndex < messages.size) {
                // 1. Append the raw chunk FIRST
                aiResponseBuilder.append(content)
                var fullText = aiResponseBuilder.toString()
                var hitStopToken = false

                // 2. Scan the ACCUMULATED string for any stop tokens
                controlTokens.forEach { token ->
                    if (fullText.contains(token)) {
                        fullText = fullText.replace(token, "")
                        hitStopToken = true
                    }
                }

                // 3. Clean up edge cases where partial tokens are left at the end 
                // (e.g., if it stops right after sending "<|")
                if (hitStopToken || isFinished) {
                    fullText = fullText.substringBeforeLast("<|").trim()
                }

                // 4. Update the UI
                messages[aiMessageIndex] = ChatMessage(
                    role = MessageRole.AI,
                    content = fullText.trimStart(),
                    isStreaming = !isFinished && !hitStopToken
                )

                // 5. Finalize and save to Room DB ONLY ONCE
                if ((isFinished || hitStopToken) && !isCompleted) {
                    isCompleted = true
                    viewModelScope.launch(Dispatchers.IO) {
                        chatDao.insertMessage(
                            ChatMessageEntity(
                                text = fullText,
                                isFromUser = false
                            )
                        )
                    }
                }
            }
        }
    }

    override fun getInterfaceVersion(): Int = 1
    override fun getInterfaceHash(): String = ""
}
                
                withContext(Dispatchers.IO) {
                    neuralBindService?.submitPrompt(contextPrompt, callback)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to submit prompt", e)
                withContext(Dispatchers.Main) {
                    if (aiMessageIndex < messages.size) {
                        messages[aiMessageIndex] = ChatMessage(
                            role = MessageRole.AI,
                            content = "Error: Failed to process request. ${e.message}",
                            isStreaming = false
                        )
                    }
                }
            }
        }
    }
    
    private suspend fun buildSlidingWindowContext(currentPrompt: String): String {
        return withContext(Dispatchers.IO) {
            val recentMessages = chatDao.getLastNMessages(SLIDING_WINDOW_SIZE).reversed()
            
            // 2. Strict Gemma 4 Format Implementation
            val contextBuilder = StringBuilder("<bos>")
            contextBuilder.append("<|turn|>system\nYou are NeuralBind, a helpful on-device AI running natively via Android HAL.<|turn|>\n")
            
            recentMessages.forEach { msg ->
                val role = if (msg.isFromUser) "user" else "model"
                contextBuilder.append("<|turn|>$role\n${msg.text}<|turn|>\n")
            }
            
            contextBuilder.append("<|turn|>user\n$currentPrompt<|turn|>\n<|turn|>model\n")
            
            val finalPrompt = contextBuilder.toString()
            Log.d(TAG, "Sliding window context: $finalPrompt")
            finalPrompt
        }
    }
    
    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatDao.clearAllMessages()
                withContext(Dispatchers.Main) {
                    messages.clear()
                    messages.add(
                        ChatMessage(
                            role = MessageRole.AI,
                            content = "Chat history cleared. How can I help you?"
                        )
                    )
                }
                Log.d(TAG, "Chat history cleared")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear history", e)
            }
        }
    }
}
