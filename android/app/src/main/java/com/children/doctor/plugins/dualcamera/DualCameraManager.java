package com.children.doctor.plugins.dualcamera;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import java.util.List;

public class DualCameraManager {

    private static final String TAG = "DualCameraManager";

    private final Context context;
    private final Handler mainHandler;
    private final EventCallback eventCallback;

    private Camera2Controller controller;
    private volatile boolean isCapturing = false;
    private PluginCall pendingCaptureCall;

    public interface EventCallback {
        void onCameraError(String error);
        void onCaptureComplete(String[] uris, String[] paths, long[] fileSizeKb);
    }

    public DualCameraManager(Context context, EventCallback callback) {
        this.context = context;
        this.eventCallback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void startPreview(PluginCall call) {
        mainHandler.post(() -> {
            if (controller != null) {
                call.reject("Preview is already running");
                return;
            }

            ViewGroup rootView = (ViewGroup) ((android.app.Activity) context)
                    .getWindow().getDecorView().findViewById(android.R.id.content);
            if (rootView == null) {
                call.reject("Cannot find root view");
                return;
            }

            controller = new Camera2Controller(context, new Camera2Controller.PreviewCallback() {
                @Override
                public void onError(String error) {
                    if (eventCallback != null) {
                        eventCallback.onCameraError(error);
                    }
                }

                @Override
                public void onCaptureComplete(String[] uris, String[] paths, long[] fileSizeKb) {
                    controller.displayPhotos(paths);
                    if (eventCallback != null) {
                        eventCallback.onCaptureComplete(uris, paths, fileSizeKb);
                    }
                }
            });

            controller.startPreview(call, rootView);
        });
    }

    public void capture(PluginCall call) {
        mainHandler.post(() -> {
            if (isCapturing) {
                call.reject("Capture already in progress");
                return;
            }
            if (controller == null) {
                call.reject("Camera not initialized");
                return;
            }

            isCapturing = true;
            pendingCaptureCall = call;

            controller.capture(new Camera2Controller.CaptureResultCallback() {
                @Override
                public void onSuccess(String[] uris, String[] paths, long[] fileSizeKb) {
                    isCapturing = false;

                    JSObject result = new JSObject();
                    for (int i = 0; i < uris.length; i++) {
                        result.put("cameraUrl" + i, uris[i]);
                        result.put("cameraPath" + i, paths[i]);
                        result.put("cameraFileSize" + i, fileSizeKb[i]);
                    }
                    result.put("cameraFileSizeUnit", "KB");
                    result.put("timestamp", System.currentTimeMillis());

                    resolveCaptureCall(result);
                }

                @Override
                public void onError(String error) {
                    isCapturing = false;
                    rejectCaptureCall(error);
                }
            });
        });
    }

    public void stopPreview(Runnable onStopped) {
        mainHandler.post(() -> {
            if (controller != null) {
                controller.stopPreview();
                controller = null;
            }
            isCapturing = false;
            pendingCaptureCall = null;
            Log.d(TAG, "Preview stopped");
            if (onStopped != null) {
                onStopped.run();
            }
        });
    }

    public void pausePreview() {
        mainHandler.post(() -> {
            if (controller != null) {
                controller.pausePreview();
            }
        });
    }

    public void resumePreview() {
        mainHandler.post(() -> {
            if (controller != null) {
                controller.resumePreview();
            }
        });
    }

    public void displayPhotos(String[] photoPaths) {
        mainHandler.post(() -> {
            if (controller != null) {
                controller.displayPhotos(photoPaths);
            }
        });
    }

    public void resumePreviewFromPhotos() {
        mainHandler.post(() -> {
            if (controller != null) {
                controller.resumePreviewFromPhotos();
            }
        });
    }

    /**
     * 设置指定 slot 摄像头的方向校准量。
     * @param slot 槽位索引（0 或 1）
     * @param extraRotateDegrees 额外旋转角（0/90/180/270）
     * @param extraMirrorNeeded true=再补一次水平镜像
     */
    public void setSlotCalibration(int slot, int extraRotateDegrees, boolean extraMirrorNeeded) {
        mainHandler.post(() -> {
            if (controller != null) {
                controller.setSlotCalibration(slot, extraRotateDegrees, extraMirrorNeeded);
            }
        });
    }

    public void getAvailableCameras(AvailableCamerasCallback callback) {
        mainHandler.post(() -> {
            try {
                List<Camera2Session.Camera2Info> cameras = Camera2Session.getAvailableCameras(context);
                callback.onResult(cameras);
            } catch (Exception e) {
                Log.e(TAG, "Failed to enumerate cameras", e);
                callback.onError(e.getMessage());
            }
        });
    }

    public void isDualCameraSupported(Consumer<Boolean> callback) {
        mainHandler.post(() -> {
            try {
                List<Camera2Session.Camera2Info> cameras = Camera2Session.getAvailableCameras(context);
                boolean supported = cameras.size() >= 2;
                Log.d(TAG, "isDualCameraSupported: " + supported + " (cameras found: " + cameras.size() + ")");
                callback.accept(supported);
            } catch (Exception e) {
                Log.e(TAG, "Failed to check dual camera support", e);
                callback.accept(false);
            }
        });
    }

    public void shutdown() {
        mainHandler.post(() -> {
            if (controller != null) {
                controller.shutdown();
                controller = null;
            }
            isCapturing = false;
            pendingCaptureCall = null;
            Log.d(TAG, "Manager shutdown complete");
        });
    }

    private void resolveCaptureCall(JSObject result) {
        if (pendingCaptureCall != null) {
            pendingCaptureCall.resolve(result);
            pendingCaptureCall = null;
        }
    }

    private void rejectCaptureCall(String error) {
        if (pendingCaptureCall != null) {
            pendingCaptureCall.reject(error);
            pendingCaptureCall = null;
        }
    }

    public interface AvailableCamerasCallback {
        void onResult(List<Camera2Session.Camera2Info> cameras);
        void onError(String error);
    }

    @FunctionalInterface
    public interface Consumer<T> {
        void accept(T value);
    }
}
