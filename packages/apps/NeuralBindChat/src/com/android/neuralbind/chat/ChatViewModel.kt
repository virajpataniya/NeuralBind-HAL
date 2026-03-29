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

import android.hardware.neuralbind.IInferenceCallback
import android.hardware.neuralbind.INeuralBind
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ChatViewModel"
private const val SERVICE_NAME = "android.hardware.neuralbind.INeuralBind/default"
private const val MODEL_PATH = "/data/vendor/gemma.gguf"

enum class MessageRole {
    USER,
    AI
}

data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val isStreaming: Boolean = false
)

class ChatViewModel : ViewModel() {
    
    val messages = mutableStateListOf<ChatMessage>()
    private var neuralBindService: INeuralBind? = null
    private var isModelLoaded = false
    
    init {
        initializeService()
    }
    
    private fun initializeService() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Waiting for NeuralBind service...")
                val binder: IBinder = ServiceManager.waitForDeclaredService(SERVICE_NAME)
                    ?: throw IllegalStateException("Failed to get service binder")
                
                neuralBindService = INeuralBind.Stub.asInterface(binder)
                Log.d(TAG, "NeuralBind service connected")
                
                // Load the model
                neuralBindService?.loadModel(MODEL_PATH)
                isModelLoaded = true
                Log.d(TAG, "Model loaded from $MODEL_PATH")
                
                withContext(Dispatchers.Main) {
                    messages.add(
                        ChatMessage(
                            role = MessageRole.AI,
                            content = "Model loaded. How can I help you today?"
                        )
                    )
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
            // Add user message
            withContext(Dispatchers.Main) {
                messages.add(ChatMessage(role = MessageRole.USER, content = prompt))
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
                // Create callback for streaming responses
                val callback = object : IInferenceCallback.Stub() {
                    override fun onResponse(content: String, isFinished: Boolean) {
                        // This is called on a Binder thread, so we need to post to main thread
                        viewModelScope.launch(Dispatchers.Main) {
                            if (aiMessageIndex < messages.size) {
                                val currentMessage = messages[aiMessageIndex]
                                messages[aiMessageIndex] = ChatMessage(
                                    role = MessageRole.AI,
                                    content = currentMessage.content + content,
                                    isStreaming = !isFinished
                                )
                            }
                        }
                    }

                    // ADD THESE TWO OVERRIDES FOR KOTLIN AIDL STRICTNESS
                    override fun getInterfaceVersion(): Int {
                        return 1
                    }

                    override fun getInterfaceHash(): String {
                        return ""
                    }
                }
                
                // Submit prompt with callback
                withContext(Dispatchers.IO) {
                    // Wrap the user's text in Gemma's required instruction tags!
                    val formattedPrompt = "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n"
                    neuralBindService?.submitPrompt(formattedPrompt, callback)
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
}
