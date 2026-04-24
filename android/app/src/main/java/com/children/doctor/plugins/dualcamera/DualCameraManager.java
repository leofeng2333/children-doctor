package com.children.doctor.plugins.dualcamera;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class DualCameraManager {

    private static final String TAG = "DualCameraManager";

    private final Context context;
    private final Handler mainHandler;
    private final PreviewCallback callback;

    private Camera2Controller controller;
    private ViewGroup containerView;

    private boolean isCapturing = false;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private PluginCall captureCall;

    private static final String PHOTO_DIR_NAME = "dual_camera_photos";

    public interface PreviewCallback {
        void onError(String error);
        void onCaptureComplete(String[] uris, String[] paths, long[] fileSizeKb);
    }

    public static class CameraSlot {
        final String cameraId;
        final Integer lensFacing;
        final String label;

        CameraSlot(String cameraId, Integer lensFacing, String label) {
            this.cameraId = cameraId;
            this.lensFacing = lensFacing;
            this.label = label;
        }
    }

    public DualCameraManager(Context context, PreviewCallback callback) {
        this.context = context;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void startPreview(PluginCall call) {
        mainHandler.post(() -> {
            if (controller != null) {
                call.reject("Preview is already running");
                return;
            }

            ViewGroup rootView = (ViewGroup) ((android.app.Activity) context).getWindow().getDecorView().findViewById(android.R.id.content);
            if (rootView == null) {
                call.reject("Cannot find root view");
                return;
            }

            controller = new Camera2Controller(context, new Camera2Controller.PreviewCallback() {
                @Override
                public void onError(String error) {
                    if (DualCameraManager.this.callback != null) {
                        DualCameraManager.this.callback.onError(error);
                    }
                }

                @Override
                public void onCaptureComplete(String[] uris, String[] paths, long[] fileSizeKb) {
                    controller.displayPhotos(paths);
                    if (DualCameraManager.this.callback != null) {
                        DualCameraManager.this.callback.onCaptureComplete(uris, paths, fileSizeKb);
                    }
                }
            });

            controller.startPreview(call, rootView);
        });
    }

    public void capture(PluginCall call) {
        if (isCapturing) {
            call.reject("Capture already in progress");
            return;
        }
        if (controller == null) {
            call.reject("Camera not initialized");
            return;
        }

        isCapturing = true;
        captureCall = call;

        controller.capture(new Camera2Controller.CaptureResultCallback() {
            @Override
            public void onSuccess(String[] uris, String[] paths, long[] fileSizeKb) {
                isCapturing = false;

                controller.displayPhotos(paths);

                JSObject result = new JSObject();
                for (int i = 0; i < uris.length; i++) {
                    result.put("cameraUrl" + i, uris[i]);
                    result.put("cameraPath" + i, paths[i]);
                    result.put("cameraFileSize" + i, fileSizeKb[i]);
                }
                result.put("cameraFileSizeUnit", "KB");
                result.put("timestamp", System.currentTimeMillis());

                if (captureCall != null) {
                    captureCall.resolve(result);
                    captureCall = null;
                }
            }

            @Override
            public void onError(String error) {
                isCapturing = false;
                if (captureCall != null) {
                    captureCall.reject(error);
                    captureCall = null;
                }
            }
        });
    }

    public void stopPreview() {
        mainHandler.post(() -> {
            if (controller != null) {
                controller.shutdown();
                controller = null;
            }

            ViewGroup rootView = (ViewGroup) ((android.app.Activity) context).getWindow().getDecorView().findViewById(android.R.id.content);
            if (rootView != null) {
                rootView.removeView(containerView);
            }
            containerView = null;

            isCapturing = false;
            Log.d(TAG, "Preview stopped");
        });
    }

    public void getAvailableCameras(AvailableCamerasCallback callback) {
        mainHandler.post(() -> {
            try {
                List<Camera2Session.Camera2Info> cameras = Camera2Session.getAvailableCameras(context);
                List<CameraSummary> result = new ArrayList<>();

                for (Camera2Session.Camera2Info info : cameras) {
                    result.add(new CameraSummary(info.cameraId, info.lensFacing, info.cameraId));
                }

                callback.onResult(result);
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

    public void uploadFiles(
        String uploadUrl,
        Map<String, String[]> files,
        Map<String, String> extraData,
        UploadCallback callback
    ) {
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String boundary = "----DualCameraUpload" + System.currentTimeMillis();
                URL url = new URL(uploadUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setDoInput(true);
                connection.setUseCaches(false);
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);

                try (DataOutputStream dos = new DataOutputStream(connection.getOutputStream())) {
                    if (extraData != null) {
                        for (Map.Entry<String, String> entry : extraData.entrySet()) {
                            dos.writeBytes("--" + boundary + "\r\n");
                            dos.writeBytes("Content-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n");
                            dos.writeBytes(entry.getValue() + "\r\n");
                        }
                    }

                    for (Map.Entry<String, String[]> fieldEntry : files.entrySet()) {
                        String fieldName = fieldEntry.getKey();
                        for (String filePath : fieldEntry.getValue()) {
                            File file = new File(filePath);
                            if (!file.exists()) {
                                mainHandler.post(() -> callback.onError("File not found: " + filePath));
                                return;
                            }

                            String fileName = file.getName();
                            String mimeType = "image/jpeg";

                            dos.writeBytes("--" + boundary + "\r\n");
                            dos.writeBytes("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n");
                            dos.writeBytes("Content-Type: " + mimeType + "\r\n\r\n");

                            try (FileInputStream fis = new FileInputStream(file)) {
                                byte[] buffer = new byte[8192];
                                int bytesRead;
                                while ((bytesRead = fis.read(buffer)) != -1) {
                                    dos.write(buffer, 0, bytesRead);
                                }
                            }
                            dos.writeBytes("\r\n");
                        }
                    }

                    dos.writeBytes("--" + boundary + "--\r\n");
                    dos.flush();
                }

                int responseCode = connection.getResponseCode();
                BufferedReader reader;
                if (responseCode >= 200 && responseCode < 300) {
                    reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                } else {
                    reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()));
                }

                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                String responseBody = response.toString();
                if (responseCode >= 200 && responseCode < 300) {
                    mainHandler.post(() -> callback.onSuccess(responseBody));
                } else {
                    mainHandler.post(() -> callback.onError("HTTP " + responseCode + ": " + responseBody));
                }
            } catch (IOException e) {
                Log.e(TAG, "Upload failed", e);
                mainHandler.post(() -> callback.onError(e.getMessage()));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    public interface UploadCallback {
        void onSuccess(String response);
        void onError(String error);
    }

    public interface AvailableCamerasCallback {
        void onResult(List<CameraSummary> cameras);
        void onError(String error);
    }

    public static class CameraSummary {
        public String cameraId;
        public Integer lensFacing;
        public String deviceId;

        public CameraSummary(String cameraId, Integer lensFacing) {
            this.cameraId = cameraId;
            this.lensFacing = lensFacing;
            this.deviceId = cameraId;
        }

        public CameraSummary(String cameraId, Integer lensFacing, String deviceId) {
            this.cameraId = cameraId;
            this.lensFacing = lensFacing;
            this.deviceId = deviceId;
        }
    }
}
