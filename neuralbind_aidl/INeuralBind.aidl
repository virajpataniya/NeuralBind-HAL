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

package android.hardware.neuralbind;

import android.hardware.neuralbind.IInferenceCallback;

@VintfStability
interface INeuralBind {
    /**
     * Load a model from the specified path.
     *
     * @param modelPath Path to the model file to load
     */
    void loadModel(in String modelPath);

    /**
     * Submit a prompt for inference.
     *
     * @param prompt The input prompt text
     * @param callback Callback interface for receiving inference results
     */
    oneway void submitPrompt(in String prompt, in IInferenceCallback callback);
}
