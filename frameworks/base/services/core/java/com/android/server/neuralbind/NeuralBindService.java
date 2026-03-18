package com.android.server.neuralbind;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.server.SystemService;

/**
 * System service for NeuralBind - manages on-device AI inference
 */
public class NeuralBindService extends SystemService {
    private static final String TAG = "NeuralBindService";

    private android.hardware.neuralbind.INeuralBind mHalService;

    public NeuralBindService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        // Service initialization - currently empty
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            try {
                Slog.i(TAG, "Connecting to NeuralBind HAL service...");
                
                IBinder binder = ServiceManager.waitForDeclaredService(
                    "android.hardware.neuralbind.INeuralBind/default");
                
                if (binder != null) {
                    mHalService = android.hardware.neuralbind.INeuralBind.Stub.asInterface(binder);
                    Slog.i(TAG, "Successfully connected to NeuralBind HAL service");
                } else {
                    Slog.e(TAG, "Failed to connect to NeuralBind HAL service: binder is null");
                }
            } catch (Exception e) {
                Slog.e(TAG, "Error connecting to NeuralBind HAL service", e);
            }
        }
    }

    /**
     * Load an AI model from the specified path
     * @param path Path to the model file
     */
    public void loadModel(String path) {
        getContext().enforceCallingOrSelfPermission(
            "android.permission.USE_NEURALBIND", 
            "Denied");

        if (mHalService == null) {
            Slog.e(TAG, "HAL service not connected");
            return;
        }

        try {
            mHalService.loadModel(path);
            Slog.i(TAG, "Model loaded successfully: " + path);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to load model: " + path, e);
        }
    }

    /**
     * Submit a prompt for inference
     * @param prompt The input prompt text
     * @param callback Callback to receive inference results
     */
    public void submitPrompt(String prompt, android.hardware.neuralbind.IInferenceCallback callback) {
        getContext().enforceCallingOrSelfPermission(
            "android.permission.USE_NEURALBIND", 
            "Denied");

        if (mHalService == null) {
            Slog.e(TAG, "HAL service not connected");
            return;
        }

        try {
            mHalService.submitPrompt(prompt, callback);
            Slog.i(TAG, "Prompt submitted successfully");
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to submit prompt", e);
        }
    }
}
