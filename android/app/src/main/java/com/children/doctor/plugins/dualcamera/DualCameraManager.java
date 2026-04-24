package com.children.doctor.plugins.dualcamera;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ConcurrentCamera;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.FileProvider;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class DualCameraManager {

    private static final String TAG = "DualCameraManager";

    private final Context context;
    private final FragmentActivity activity;
    private final Handler mainHandler;
    private final PreviewCallback callback;

    private ProcessCameraProvider cameraProvider;
    private CameraSlot[] slots;

    private ViewGroup containerView;

    private boolean isCapturing = false;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    private PluginCall captureCall;

    private static final String PHOTO_DIR_NAME = "dual_camera_photos";

    public interface PreviewCallback {
        void onError(String error);
        void onCaptureComplete(String[] uris, String[] paths, long[] fileSizeKb);
    }

    public static class CameraSlot {
        final CameraSelector selector;
        final Preview preview;
        final ImageCapture imageCapture;
        final FrameLayout container;
        final PreviewView previewView;
        String pendingPath;
        Uri capturedUri;
        long capturedFileSizeKb;
        final Integer lensFacing;
        final String label;
        androidx.camera.core.CameraInfo nativeCameraInfo;
        String cameraId;

        CameraSlot(Context context, CameraSelector selector, String label, Integer lensFacing, androidx.camera.core.CameraInfo info, String cameraId) {
            this.selector = selector;
            this.lensFacing = lensFacing;
            this.label = label;
            this.nativeCameraInfo = info;
            this.cameraId = cameraId;
            this.preview = new Preview.Builder()
                    .setTargetResolution(new Size(1080, 1440))
                    .setTargetFrameRate(Range.<Integer>create(10, 15))
                    .build();
            this.imageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetResolution(new Size(1080, 1440))
                    .setJpegQuality(85)
                    .build();
            this.container = new FrameLayout(context);
            this.previewView = new PreviewView(context);
        }
    }

    public DualCameraManager(Context context, FragmentActivity activity, PreviewCallback callback) {
        this.context = context;
        this.activity = activity;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void startPreview(PluginCall call) {
        mainHandler.post(() -> {
            ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(context);

            cameraProviderFuture.addListener(() -> {
                try {
                    cameraProvider = cameraProviderFuture.get();
                    List<androidx.camera.core.CameraInfo> available =
                        cameraProvider.getAvailableCameraInfos();

                    CameraSlot[] selected = buildSlots(context, available, 2);
                    if (selected == null) {
                        call.reject("Need at least 1 camera, found " + available.size());
                        return;
                    }
                    slots = selected;

                    setupPreviewViews(new BindResultCallback() {
                        @Override
                        public void onResult(BindResult result) {
                            try {
                                JSArray camList = new JSArray();
                                for (CameraSummary cam : result.cameras) {
                                    JSObject camJson = new JSObject();
                                    camJson.put("cameraId", cam.cameraId);
                                    camJson.put("lensFacing", cam.lensFacing);
                                    camList.put(camJson);
                                }
                                JSObject ret = new JSObject();
                                ret.put("cameras", camList);
                                ret.put("concurrent", result.concurrent);
                                call.resolve(ret);
                            } catch (Exception e) {
                                call.reject("Failed to build result", e);
                            }
                        }

                        @Override
                        public void onError(String error) {
                            call.reject(error);
                        }
                    });
                } catch (ExecutionException | InterruptedException e) {
                    Log.e(TAG, "Failed to get camera provider", e);
                    call.reject("Failed to get camera provider: " + e.getMessage());
                }
            }, ContextCompat.getMainExecutor(context));
        });
    }

    private CameraSlot[] buildSlots(Context ctx, List<androidx.camera.core.CameraInfo> available, int maxSlots) {
        CameraSlot[] result = new CameraSlot[maxSlots];
        int slot = 0;
        String[] labels = new String[]{"正视图", "右侧视图", "摄像头3", "摄像头4"};

        for (androidx.camera.core.CameraInfo info : available) {
            if (slot >= maxSlots) break;
            Integer facing = info.getLensFacing();
            Log.d(TAG, "CameraInfo: cameraId=" + Camera2CameraInfo.from(info).getCameraId()
                + ", lensFacing=" + facing
                + ", sensorRotation=" + info.getSensorRotationDegrees()
                + ", hasFlash=" + info.hasFlashUnit());

            CameraSelector.Builder builder = new CameraSelector.Builder();

            if (facing != null) {
                builder.requireLensFacing(facing);
            } else {
                String cameraId = Camera2CameraInfo.from(info).getCameraId();
                builder.addCameraFilter(cameraInfos -> {
                    List<androidx.camera.core.CameraInfo> filtered = new ArrayList<>();
                    for (androidx.camera.core.CameraInfo ci : cameraInfos) {
                        if (Camera2CameraInfo.from(ci).getCameraId().equals(cameraId)) {
                            filtered.add(ci);
                            break;
                        }
                    }
                    return filtered;
                });
            }

            result[slot++] = new CameraSlot(ctx, builder.build(), labels[slot - 1], facing, info, Camera2CameraInfo.from(info).getCameraId());
        }

        if (slot == 0) return null;
        return Arrays.copyOf(result, slot);
    }

    private void setupPreviewViews(BindResultCallback callback) {
        for (CameraSlot s : slots) {
            s.previewView.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
            s.previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
            s.preview.setSurfaceProvider(s.previewView.getSurfaceProvider());

            s.container.setBackgroundColor(0xFF000000);
            s.previewView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            s.container.addView(s.previewView);
        }

        ViewGroup rootView = (ViewGroup) activity.getWindow().getDecorView().findViewById(android.R.id.content);
        containerView = new LinearLayout(context);
        containerView.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ((LinearLayout) containerView).setGravity(Gravity.CENTER);
        if (slots.length == 1) {
            ((LinearLayout) containerView).setOrientation(LinearLayout.VERTICAL);
        } else {
            ((LinearLayout) containerView).setOrientation(LinearLayout.HORIZONTAL);
        }

        int screenWidthPx = context.getResources().getDisplayMetrics().widthPixels;
        int colMargin = dpToPx(16);

        for (int i = 0; i < slots.length; i++) {
            CameraSlot s = slots[i];
            int w = slots.length == 1 ? (int) (screenWidthPx * 0.85f)
                                     : (int) (screenWidthPx * 0.415f);
            int h = (int) (w * 4f / 3f);

            TextView label = new TextView(context);
            label.setText(s.label);
            label.setTextSize(14);
            label.setTextColor(0xFF000000);
            label.setGravity(Gravity.CENTER);
            label.setBackgroundColor(0x00000000);
            label.setPadding(0, dpToPx(4), 0, dpToPx(8));
            label.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            FrameLayout.LayoutParams conParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h);
            conParams.setMargins(0, dpToPx(12), 0, dpToPx(12));
            s.container.setLayoutParams(conParams);

            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams colParams = new LinearLayout.LayoutParams(w, h + dpToPx(60));
            int marginStart = slots.length == 1 ? colMargin
                                : (i == 0 ? colMargin : colMargin / 2);
            int marginEnd = slots.length == 1 ? colMargin
                                : (i == slots.length - 1 ? colMargin : colMargin / 2);
            colParams.setMargins(marginStart, 0, marginEnd, 0);
            col.setLayoutParams(colParams);
            col.addView(label);
            col.addView(s.container);

            containerView.addView(col);
        }

        rootView.addView(containerView);
        bindCameras(callback);
    }

    private void bindCameras(BindResultCallback callback) {
        if (cameraProvider == null) {
            callback.onError("Camera provider not ready");
            return;
        }

        if (slots.length == 1) {
            trySequentialBind(callback);
            return;
        }

        try {
            cameraProvider.unbindAll();

            List<ConcurrentCamera.SingleCameraConfig> configs = new ArrayList<>();
            for (CameraSlot s : slots) {
                UseCaseGroup ug = new UseCaseGroup.Builder()
                        .addUseCase(s.preview)
                        .addUseCase(s.imageCapture)
                        .build();
                configs.add(new ConcurrentCamera.SingleCameraConfig(s.selector, ug, activity));
            }

            cameraProvider.bindToLifecycle(configs);
            Log.d(TAG, "ConcurrentCamera bind: " + slots.length + " cameras");
            CameraSummary[] camInfos = buildCameraInfos();
            callback.onResult(new BindResult(true, camInfos));
        } catch (Exception e) {
            Log.e(TAG, "ConcurrentCamera bind failed, falling back to sequential", e);
            trySequentialBind(callback);
        }
    }

    private void trySequentialBind(BindResultCallback callback) {
        try {
            cameraProvider.unbindAll();
            for (CameraSlot s : slots) {
                cameraProvider.bindToLifecycle(activity, s.selector, s.preview, s.imageCapture);
                Log.d(TAG, "Sequential bind: " + s.label);
            }
            CameraSummary[] camInfos = buildCameraInfos();
            callback.onResult(new BindResult(false, camInfos));
        } catch (Exception e) {
            Log.e(TAG, "Sequential bind failed", e);
            callback.onError("Failed to bind cameras: " + e.getMessage());
        }
    }

    private CameraSummary[] buildCameraInfos() {
        CameraSummary[] infos = new CameraSummary[slots.length];
        for (int i = 0; i < slots.length; i++) {
            CameraSlot s = slots[i];
            infos[i] = new CameraSummary(s.cameraId, s.lensFacing);
        }
        return infos;
    }

    public void capture(PluginCall call) {
        if (isCapturing) {
            call.reject("Capture already in progress");
            return;
        }
        if (slots == null || slots.length == 0) {
            call.reject("Camera not initialized");
            return;
        }

        isCapturing = true;
        captureCall = call;

        File photoDir = new File(context.getCacheDir(), PHOTO_DIR_NAME);
        if (!photoDir.exists()) {
            photoDir.mkdirs();
        }

        String timestamp = String.valueOf(System.currentTimeMillis());
        String[] labels = {"front", "back"};
        for (int i = 0; i < slots.length; i++) {
            slots[i].pendingPath = new File(photoDir, labels[i] + "_" + timestamp + ".jpg").getAbsolutePath();
        }

        captureNext(0);
    }

    private void captureNext(int index) {
        if (index >= slots.length) {
            finishCapture();
            return;
        }

        CameraSlot s = slots[index];
        boolean isFront = s.lensFacing == CameraSelector.LENS_FACING_FRONT;
        s.imageCapture.takePicture(executor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                saveImage(image, s.pendingPath, isFront, (success) -> {
                    image.close();
                    if (!success) {
                        isCapturing = false;
                        if (captureCall != null) {
                            captureCall.reject(s.label + " save failed");
                            captureCall = null;
                        }
                        return;
                    }
                    File f = new File(s.pendingPath);
                    s.capturedFileSizeKb = f.length() / 1024;
                    s.capturedUri = FileProvider.getUriForFile(context,
                            context.getPackageName() + ".fileprovider", f);
                    captureNext(index + 1);
                });
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, s.label + " capture failed", exception);
                isCapturing = false;
                if (captureCall != null) {
                    captureCall.reject(s.label + " capture failed: " + exception.getMessage());
                    captureCall = null;
                }
            }
        });
    }

    private void finishCapture() {
        isCapturing = false;

        String[] uris = new String[slots.length];
        String[] paths = new String[slots.length];
        long[] fileSizeKb = new long[slots.length];
        for (int i = 0; i < slots.length; i++) {
            uris[i] = slots[i].capturedUri != null ? slots[i].capturedUri.toString() : null;
            paths[i] = slots[i].pendingPath;
            fileSizeKb[i] = slots[i].capturedFileSizeKb;
        }

        JSObject result = new JSObject();
        for (int i = 0; i < slots.length; i++) {
            result.put("cameraUrl" + i, uris[i]);
            result.put("cameraPath" + i, paths[i]);
            result.put("cameraFileSize" + i, fileSizeKb[i]);
            result.put("cameraFileSizeUnit", "KB");
        }
        result.put("timestamp", System.currentTimeMillis());

        mainHandler.post(() -> {
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
                cameraProvider = null;
            }
            displayCapturedPhotos();

            if (captureCall != null) {
                captureCall.resolve(result);
                captureCall = null;
            }

            if (callback != null) {
                callback.onCaptureComplete(uris, paths, fileSizeKb);
            }
        });
    }

    private void displayCapturedPhotos() {
        for (CameraSlot s : slots) {
            if (s.capturedUri != null) {
                s.container.removeAllViews();
                ImageView iv = new ImageView(context);
                iv.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setImageURI(s.capturedUri);
                s.container.addView(iv);
            }
        }
    }

    private void saveImage(ImageProxy image, String filePath, boolean isFront, Consumer<Boolean> onComplete) {
        executor.execute(() -> {
            try {
                Bitmap bitmap = image.toBitmap();
                if (bitmap == null) {
                    Log.e(TAG, "toBitmap() returned null");
                    mainHandler.post(() -> onComplete.accept(false));
                    return;
                }

                int rotationDegrees = image.getImageInfo().getRotationDegrees();

                Matrix matrix = new Matrix();
                if (rotationDegrees != 0) {
                    matrix.postRotate(rotationDegrees);
                }

                if (isFront) {
                    matrix.postScale(-1, 1);
                }

                Bitmap finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (finalBitmap == null) {
                    Log.e(TAG, "createBitmap returned null");
                    bitmap.recycle();
                    mainHandler.post(() -> onComplete.accept(false));
                    return;
                }

                File file = new File(filePath);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos);
                }

                if (!bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                if (finalBitmap != bitmap && !finalBitmap.isRecycled()) {
                    finalBitmap.recycle();
                }

                Log.d(TAG, "Image saved: " + filePath + " (" + file.length() + " bytes, " + image.getWidth() + "x" + image.getHeight() + ", rotation=" + rotationDegrees + ")");
                mainHandler.post(() -> onComplete.accept(true));
            } catch (Exception e) {
                Log.e(TAG, "Failed to save image", e);
                mainHandler.post(() -> onComplete.accept(false));
            }
        });
    }

    public void stopPreview() {
        mainHandler.post(() -> {
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
                cameraProvider = null;
            }

            if (containerView != null) {
                ViewGroup rootView = (ViewGroup) activity.getWindow().getDecorView().findViewById(android.R.id.content);
                if (rootView != null) {
                    rootView.removeView(containerView);
                }
                containerView = null;
            }

            slots = null;

            Log.d(TAG, "Preview stopped");
        });
    }

    private int dpToPx(int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }

    public interface UploadCallback {
        void onSuccess(String response);
        void onError(String error);
    }

    public interface AvailableCamerasCallback {
        void onResult(java.util.List<CameraSummary> cameras);
        void onError(String error);
    }

    public static class BindResult {
        public boolean concurrent;
        public CameraSummary[] cameras;

        public BindResult(boolean concurrent, CameraSummary[] cameras) {
            this.concurrent = concurrent;
            this.cameras = cameras;
        }
    }

    public interface BindResultCallback {
        void onResult(BindResult result);
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

    public void getAvailableCameras(AvailableCamerasCallback callback) {
        mainHandler.post(() -> {
            try {
                CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                java.util.List<CameraSummary> result = new java.util.ArrayList<>();

                for (String cameraId : cameraManager.getCameraIdList()) {
                    CameraCharacteristics chars = cameraManager.getCameraCharacteristics(cameraId);
                    Integer lensFacing = null;
                    Integer facingVal = chars.get(CameraCharacteristics.LENS_FACING);
                    if (facingVal != null) {
                        lensFacing = facingVal;
                    }
                    result.add(new CameraSummary(cameraId, lensFacing, cameraId));
                    Log.d(TAG, "Camera2Enum: id=" + cameraId + ", facing=" + lensFacing);
                }

                callback.onResult(result);
            } catch (Exception e) {
                Log.e(TAG, "Failed to enumerate cameras via Camera2", e);
                callback.onError(e.getMessage());
            }
        });
    }

    public void isDualCameraSupported(Consumer<Boolean> callback) {
        mainHandler.post(() -> {
            ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(context);

            cameraProviderFuture.addListener(() -> {
                try {
                    ProcessCameraProvider provider = cameraProviderFuture.get();

                    List<List<androidx.camera.core.CameraInfo>> concurrentInfos =
                            provider.getAvailableConcurrentCameraInfos();

                    boolean supported = false;
                    for (List<androidx.camera.core.CameraInfo> group : concurrentInfos) {
                        if (group.size() >= 2) {
                            supported = true;
                            break;
                        }
                    }

                    Log.d(TAG, "isDualCameraSupported: " + supported + " (concurrent groups: " + concurrentInfos.size() + ")");
                    callback.accept(supported);
                } catch (ExecutionException | InterruptedException e) {
                    Log.e(TAG, "Failed to check dual camera support", e);
                    callback.accept(false);
                }
            }, ContextCompat.getMainExecutor(context));
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
                            if (fileName.toLowerCase().endsWith(".png")) {
                                mimeType = "image/png";
                            } else if (fileName.toLowerCase().endsWith(".webp")) {
                                mimeType = "image/webp";
                            }

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
}
