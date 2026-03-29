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

package com.android.commands.neuralbindtest;

import android.hardware.neuralbind.IInferenceCallback;
import android.hardware.neuralbind.INeuralBind;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.util.concurrent.CountDownLatch;

public class NeuralBindTest {
    private static final String SERVICE_NAME = "android.hardware.neuralbind.INeuralBind/default";
    private static final String MODEL_PATH = "/data/vendor/gemma.gguf";

    public static void main(String[] args) {
        try {
            System.out.println("NeuralBind HAL Test - Connecting to service...");

            // Get the service
            IBinder binder = ServiceManager.waitForDeclaredService(SERVICE_NAME);
            if (binder == null) {
                System.err.println("Failed to get service: " + SERVICE_NAME);
                System.exit(1);
            }

            INeuralBind service = INeuralBind.Stub.asInterface(binder);
            System.out.println("Connected to NeuralBind service");

            // Load the model
            System.out.println("Loading model: " + MODEL_PATH);
            service.loadModel(MODEL_PATH);
            System.out.println("Model loaded successfully");

            // Create callback with latch to wait for completion
            final CountDownLatch latch = new CountDownLatch(1);
            IInferenceCallback callback = new IInferenceCallback.Stub() {
                @Override
                public void onResponse(String content, boolean isFinished) {
                    System.out.print(content);
                    if (isFinished) {
                        System.out.println();
                        latch.countDown();
                    }
                }

                @Override
                public int getInterfaceVersion() {
                    return this.VERSION;
                }

                @Override
                public String getInterfaceHash() {
                    return this.HASH;
                }
            };

            String prompt = "Write a short haiku about Android development."; // Fallback default
            if (args.length > 0) {
                // Join all command line arguments into a single string
                prompt = String.join(" ", args);
            }

            // Submit prompt
            System.out.println("\nSubmitting prompt: \"" + prompt + "\"");
            System.out.println("Response:\n");
            service.submitPrompt(prompt, callback);

            // Wait for completion
            latch.await();

            System.out.println("\nTest completed successfully");
            System.exit(0);

        } catch (RemoteException e) {
            System.err.println("RemoteException: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } catch (InterruptedException e) {
            System.err.println("Interrupted while waiting for response");
            e.printStackTrace();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
