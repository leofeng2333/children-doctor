package com.children.doctor.plugins.dualcamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Camera2Controller {

    private static final String TAG = "Camera2Controller";
    private static final String PHOTO_DIR_NAME = "dual_camera_photos";
    private static final int CAPTURE_TIMEOUT_SECONDS = 15;

    private static volatile CaptureLogger captureLogger;

    public static void setCaptureLogger(CaptureLogger logger) {
        captureLogger = logger;
    }

    private static void log(String msg) {
        if (captureLogger != null) captureLogger.java(TAG, msg);
    }

    private final Context context;
    private final Handler mainHandler;
    private final PreviewCallback callback;

    private TextureView[] textureViews;
    private android.widget.ImageView[] photoImageViews;
    private Camera2Session[] sessions;
    private android.widget.LinearLayout containerView;
    private int slotCount;
    private int screenWidthPx;

    private final AtomicBoolean isCapturing = new AtomicBoolean(false);
    private final AtomicBoolean isStopped = new AtomicBoolean(false);

    private PluginCall pendingCall;
    private int openedCount = 0;
    private int failedCount = 0;
    private String[] slotErrors;

    private static final String[] CAMERA_LABELS = {"正视图", "右侧视图"};

    public interface PreviewCallback {
        void onError(String error);
        void onCaptureComplete(String[] uris, String[] paths, long[] fileSizeKb);
    }

    public interface CaptureResultCallback {
        void onSuccess(String[] uris, String[] paths, long[] fileSizeKb);
        void onError(String error);
    }

    public Camera2Controller(Context context, PreviewCallback callback) {
        this.context = context;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void startPreview(PluginCall call, ViewGroup rootView) {
        List<Camera2Session.Camera2Info> cameras = Camera2Session.getAvailableCameras(context);

        if (cameras.isEmpty()) {
            call.reject("No cameras found");
            return;
        }

        if (cameras.size() < 2) {
            Log.w(TAG, "Only " + cameras.size() + " camera(s) found, using single camera mode");
        }

        slotCount = Math.min(cameras.size(), 2);
        textureViews = new TextureView[slotCount];
        photoImageViews = new android.widget.ImageView[slotCount];
        sessions = new Camera2Session[slotCount];

        for (int i = 0; i < slotCount; i++) {
            sessions[i] = new Camera2Session(
                    context,
                    cameras.get(i).cameraId,
                    cameras.get(i).lensFacing,
                    cameras.get(i).previewSize,
                    cameras.get(i).captureSize
            );
        }

        isStopped.set(false);
        openedCount = 0;
        failedCount = 0;
        slotErrors = new String[slotCount];
        pendingCall = call;
        buildTextureViews(rootView, slotCount);
    }

    private void buildTextureViews(ViewGroup rootView, int slotCount) {
        android.widget.LinearLayout container = new android.widget.LinearLayout(context);
        container.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.setGravity(android.view.Gravity.CENTER);
        container.setOrientation(slotCount == 1
                ? android.widget.LinearLayout.VERTICAL
                : android.widget.LinearLayout.HORIZONTAL);

        containerView = container;
        screenWidthPx = context.getResources().getDisplayMetrics().widthPixels;
        int colMargin = dpToPx(16);

        for (int i = 0; i < slotCount; i++) {
            final int slot = i;
            TextureView textureView = new TextureView(context);
            textureViews[i] = textureView;

            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                private Surface surface;
                private SurfaceTexture surfaceTexture;

                private void openCameraIfReady() {
                    if (isStopped.get() || surface == null || surfaceTexture == null
                            || sessions == null || sessions[slot] == null) {
                        return;
                    }
                    sessions[slot].open(surface, surfaceTexture, buildSessionCallback(slot));
                }

                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int width, int height) {
                    Log.d(TAG, "SurfaceTexture available: " + width + "x" + height + ", slot=" + slot);
                    if (sessions == null || sessions[slot] == null) return;

                    Size preview = sessions[slot].getPreviewSize();
                    st.setDefaultBufferSize(preview.getWidth(), preview.getHeight());
                    surfaceTexture = st;
                    surface = new Surface(surfaceTexture);
                    mainHandler.post(this::openCameraIfReady);
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int width, int height) {}

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
                    if (sessions != null && sessions[slot] != null) {
                        sessions[slot].close();
                    }
                    if (surface != null) {
                        surface.release();
                        surface = null;
                    }
                    if (surfaceTexture != null) {
                        surfaceTexture.release();
                        surfaceTexture = null;
                    }
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {}
            });

            ViewGroup root = buildCameraSlotView(slot, textureView, colMargin);
            container.addView(root);
        }

        rootView.addView(container);
    }

    private ViewGroup buildCameraSlotView(int index, TextureView textureView, int colMargin) {
        int w = slotCount == 1
                ? (int) (screenWidthPx * 0.85f)
                : (int) (screenWidthPx * 0.415f);
        int h = (int) (w * 4f / 3f);

        android.widget.TextView label = new android.widget.TextView(context);
        label.setText(CAMERA_LABELS[index]);
        label.setTextSize(14);
        label.setTextColor(0xFF333333);
        label.setGravity(android.view.Gravity.CENTER);
        label.setPadding(0, dpToPx(4), 0, dpToPx(8));
        label.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.widget.FrameLayout.LayoutParams tvParams =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, h);
        tvParams.setMargins(0, dpToPx(12), 0, dpToPx(12));
        textureView.setLayoutParams(tvParams);
        textureView.setVisibility(android.view.View.VISIBLE);

        android.widget.ImageView photoView = new android.widget.ImageView(context);
        photoImageViews[index] = photoView;
        android.widget.FrameLayout.LayoutParams photoParams =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, h);
        photoParams.setMargins(0, dpToPx(12), 0, dpToPx(12));
        photoView.setLayoutParams(photoParams);
        photoView.setVisibility(android.view.View.GONE);

        android.widget.FrameLayout previewWrapper = new android.widget.FrameLayout(context);
        previewWrapper.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h + dpToPx(24)));
        previewWrapper.addView(textureView);
        previewWrapper.addView(photoView);

        android.widget.LinearLayout col = new android.widget.LinearLayout(context);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setGravity(android.view.Gravity.CENTER);

        android.widget.LinearLayout.LayoutParams colParams =
                new android.widget.LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        int marginStart = slotCount == 1 ? colMargin
                : (index == 0 ? colMargin : colMargin / 2);
        int marginEnd = slotCount == 1 ? colMargin
                : (index == slotCount - 1 ? colMargin : colMargin / 2);
        colParams.setMargins(marginStart, 0, marginEnd, 0);
        col.setLayoutParams(colParams);

        col.addView(label);
        col.addView(previewWrapper);
        return col;
    }

    private Camera2Session.Callback buildSessionCallback(int slot) {
        return new Camera2Session.Callback() {
            @Override
            public void onOpened() {
                if (isStopped.get()) return;
                synchronized (Camera2Controller.this) {
                    openedCount++;
                    Log.d(TAG, "Camera slot " + slot + " opened (opened=" + openedCount + "/" + slotCount + ")");
                    if (openedCount == slotCount) {
                        onAllCamerasOpened();
                    }
                }
            }

            @Override
            public void onDisconnected() {
                if (isStopped.get()) return;
                synchronized (Camera2Controller.this) {
                    Log.w(TAG, "Camera slot " + slot + " disconnected");
                    slotErrors[slot] = "Camera disconnected";
                    failedCount++;
                    if (openedCount + failedCount == slotCount) {
                        if (openedCount > 0) {
                            onAllCamerasOpened();
                        } else {
                            // 全部断开，释放这些已 open 失败的 session 占用的 cameraDevice
                            closeFailedSessions();
                            rejectPendingCall("All cameras disconnected");
                        }
                    }
                }
            }

            @Override
            public void onError(String error) {
                if (isStopped.get()) return;
                synchronized (Camera2Controller.this) {
                    Log.e(TAG, "Camera slot " + slot + " failed permanently: " + error);
                    slotErrors[slot] = error;
                    failedCount++;
                    if (openedCount + failedCount == slotCount) {
                        // 收尾：失败 slot 的 session 也要关掉，避免 cameraDevice 占用泄漏
                        closeFailedSessions();
                        if (openedCount > 0) {
                            Log.w(TAG, "Some cameras failed to open, proceeding with " + openedCount + " camera(s)");
                            for (int i = 0; i < slotCount; i++) {
                                if (slotErrors[i] != null) {
                                    Log.w(TAG, "  Slot " + i + " failed: " + slotErrors[i]);
                                }
                            }
                            onAllCamerasOpened();
                        } else {
                            rejectPendingCall("Failed to open all cameras: " + slotErrors[0]);
                        }
                    }
                }
            }
        };
    }

    private void closeFailedSessions() {
        if (sessions == null) return;
        for (int i = 0; i < sessions.length; i++) {
            if (slotErrors[i] != null && sessions[i] != null) {
                Log.d(TAG, "Closing failed session at slot " + i);
                try {
                    sessions[i].close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing failed session " + i, e);
                }
            }
        }
    }

    private void onAllCamerasOpened() {
        if (isStopped.get()) return;
        mainHandler.post(() -> {
            if (isStopped.get() || sessions == null) return;
            try {
                // 收集每槽的最终状态——只要有一个 slot 失败，都告诉前端"concurrent=false"
                // 让前端知道"双摄"已经退化成"单摄"，可走单路拍照回退路径。
                JSArray camList = new JSArray();
                boolean allSlotsOk = true;
                StringBuilder partialErr = new StringBuilder();
                for (int i = 0; i < sessions.length; i++) {
                    JSObject camJson = new JSObject();
                    camJson.put("cameraId", sessions[i].getCameraId());
                    camJson.put("lensFacing", sessions[i].getLensFacing());
                    camJson.put("slot", i);
                    if (slotErrors != null && slotErrors[i] != null) {
                        allSlotsOk = false;
                        camJson.put("error", slotErrors[i]);
                        partialErr.append("slot").append(i).append(":").append(slotErrors[i]).append("; ");
                    }
                    camList.put(camJson);
                }
                JSObject ret = new JSObject();
                ret.put("cameras", camList);
                // 只有所有 slot 真正打开时才报 concurrent=true，否则视为部分失败
                ret.put("concurrent", allSlotsOk && sessions.length >= 2);
                if (!allSlotsOk) {
                    String failed = partialErr.toString();
                    Log.w(TAG, "Partial-fail: " + failed);
                    ret.put("partialFail", true);
                    ret.put("partialFailReason", failed);
                }
                resolvePendingCall(ret);
            } catch (Exception e) {
                rejectPendingCall("Failed to build result: " + e.getMessage());
            }
        });
    }

    public void capture(CaptureResultCallback resultCallback) {
        if (!isCapturing.compareAndSet(false, true)) {
            resultCallback.onError("Capture already in progress");
            return;
        }
        if (sessions == null || sessions.length == 0) {
            isCapturing.set(false);
            resultCallback.onError("Camera not initialized");
            return;
        }

        // partial-fail 防护：如果已经在 startPreview 阶段判定有 slot 失败，
        // 不让用户继续拍照——半张照片毫无意义，且会让用户困惑。
        if (slotErrors != null && slotCount > 0) {
            StringBuilder pending = new StringBuilder();
            for (int i = 0; i < slotCount; i++) {
                if (slotErrors[i] != null) {
                    pending.append("slot").append(i).append(":").append(slotErrors[i]).append("; ");
                }
            }
            if (pending.length() > 0) {
                isCapturing.set(false);
                resultCallback.onError("Cannot capture: some cameras failed to open (" + pending + "). Please restart preview.");
                return;
            }
        }

        log("capture begin, slotCount=" + sessions.length
                + " timeout=" + CAPTURE_TIMEOUT_SECONDS + "s");

        File photoDir = new File(context.getCacheDir(), PHOTO_DIR_NAME);
        if (!photoDir.exists() && !photoDir.mkdirs()) {
            isCapturing.set(false);
            resultCallback.onError("Failed to create photo directory");
            return;
        }

        String timestamp = String.valueOf(System.currentTimeMillis());
        // 文件路径必须按槽位（slot）独立命名，不能依赖 lensFacing：
        //   - 多个 USB UVC 外接摄像头的 HAL 经常报告同样的 LENS_FACING 值
        //     （rockchip 上两路 UVC 都被映射成同一类，见 capture_logs 中多次出现的
        //     "slot 0 (front) ... slot 1 (front) captured"）；
        //   - 一旦两路都被打上相同的 "front" / "back" 前缀，filePaths 数组里就会出现
        //     两条相同的路径，下游 FileOutputStream(filePath) 会互相覆盖，最终只剩
        //     一张照片返回给前端 → 双屏展示同一张图。
        // 用 slot index 作为命名前缀，cameraId 作为副前缀，保证每个槽位物理上写到独立文件。
        String[] filePaths = new String[sessions.length];
        String[] slotLabels = new String[sessions.length];
        for (int i = 0; i < sessions.length; i++) {
            String camId = sessions[i].getCameraId() != null ? sessions[i].getCameraId() : ("id" + i);
            // cameraId 里可能含非文件安全字符，做一次最小清洗
            String safeCamId = camId.replaceAll("[^A-Za-z0-9_\\-]", "_");
            filePaths[i] = new File(photoDir,
                    "slot" + i + "_" + safeCamId + "_" + timestamp + ".jpg").getAbsolutePath();
            // 仅用于日志：清楚标出"第 i 个槽位、cameraId、报告的 lensFacing"
            // 这里 lensFacing 信息只是观察用途，不再参与文件命名，所以即使 HAL
            // 给两个摄像头返回同样的值也不会再导致文件覆盖。
            String facingTag = (sessions[i].getLensFacing()
                    == CameraCharacteristics.LENS_FACING_FRONT) ? "facing=front"
                    : (sessions[i].getLensFacing()
                            == CameraCharacteristics.LENS_FACING_BACK) ? "facing=back"
                    : (sessions[i].getLensFacing()
                            == CameraCharacteristics.LENS_FACING_EXTERNAL) ? "facing=external"
                    : ("facing=" + sessions[i].getLensFacing());
            slotLabels[i] = "slot" + i + "," + facingTag + ",camId=" + safeCamId;
        }

        CountDownLatch latch = new CountDownLatch(sessions.length);
        StringBuilder errors = new StringBuilder();
        String[] capturedPaths = new String[sessions.length];
        long[] capturedSizes = new long[sessions.length];

        for (int i = 0; i < sessions.length; i++) {
            final int slot = i;
            sessions[i].capture(filePaths[i], latch, new Camera2Session.CaptureCallback() {
                @Override
                public void onCaptureSuccess(String filePath, long fileSizeKb) {
                    capturedPaths[slot] = filePath;
                    capturedSizes[slot] = fileSizeKb;
                    Log.d(TAG, "Slot " + slot + " captured: " + filePath + " (" + fileSizeKb + " KB)");
                    log("slot " + slot + " (" + slotLabels[slot] + ") captured, "
                            + fileSizeKb + " KB");
                    latch.countDown();
                }

                @Override
                public void onCaptureError(String error) {
                    log("slot " + slot + " (" + slotLabels[slot] + ") error: " + error);
                    synchronized (errors) {
                        errors.append(slotLabels[slot]).append(": ").append(error).append("; ");
                    }
                    latch.countDown();
                }
            });
        }

        new Thread(() -> {
            try {
                boolean completed = latch.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                isCapturing.set(false);

                if (!completed) {
                    log("capture LATCH-TIMEOUT after " + CAPTURE_TIMEOUT_SECONDS
                            + "s, some slots did not respond");
                    mainHandler.post(() -> resultCallback.onError("Capture timeout"));
                    return;
                }

                if (errors.length() > 0) {
                    log("capture done with errors: " + errors);
                    mainHandler.post(() -> resultCallback.onError("Capture errors: " + errors));
                    return;
                }

                log("capture OK, all slots succeeded");
                String[] uris = buildFileUris(capturedPaths);
                mainHandler.post(() -> {
                    resultCallback.onSuccess(uris, capturedPaths, capturedSizes);
                    if (callback != null) {
                        callback.onCaptureComplete(uris, capturedPaths, capturedSizes);
                    }
                });
            } catch (InterruptedException e) {
                isCapturing.set(false);
                mainHandler.post(() -> resultCallback.onError("Capture interrupted"));
            }
        }).start();
    }

    private String[] buildFileUris(String[] paths) {
        String[] uris = new String[paths.length];
        for (int i = 0; i < paths.length; i++) {
            if (paths[i] != null) {
                uris[i] = FileProvider.getUriForFile(
                        context,
                        context.getPackageName() + ".fileprovider",
                        new File(paths[i])
                ).toString();
            }
        }
        return uris;
    }

    public void displayPhotos(String[] photoPaths) {
        mainHandler.post(() -> {
            if (photoImageViews == null || photoPaths == null) return;

            Log.d(TAG, "displayPhotos: " + photoImageViews.length + " slots");

            for (int i = 0; i < photoImageViews.length; i++) {
                if (photoImageViews[i] == null || photoPaths[i] == null) continue;
                displaySinglePhoto(i, photoPaths[i]);
            }
            Log.d(TAG, "displayPhotos done");
        });
    }

    private void displaySinglePhoto(int index, String path) {
        int targetW = textureViews != null && textureViews[index] != null && textureViews[index].getWidth() > 0
                ? textureViews[index].getWidth()
                : (int) (screenWidthPx * 0.415f);
        int targetH = (int) (targetW * 4f / 3f);

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        opts.inSampleSize = calculateInSampleSize(opts, targetW, targetH);
        opts.inJustDecodeBounds = false;

        Bitmap rawBmp = BitmapFactory.decodeFile(path, opts);
        if (rawBmp == null) {
            Log.e(TAG, "Slot " + index + ": BitmapFactory.decodeFile returned null");
            return;
        }

        Log.d(TAG, "Slot " + index + ": rawBitmap=" + rawBmp.getWidth() + "x" + rawBmp.getHeight());

        final int slot = index;
        final Bitmap bmpToShow = rawBmp;
        final TextureView tvRef = textureViews != null ? textureViews[index] : null;
        final android.widget.ImageView photoView = photoImageViews[index];

        photoView.post(() -> {
            if (photoView.getVisibility() == android.view.View.GONE) {
                photoView.setImageBitmap(bmpToShow);
                photoView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                animatePhotoTransition(slot, tvRef);
            }
        });
    }

    private void animatePhotoTransition(int slot, TextureView tvRef) {
        photoImageViews[slot].setAlpha(0f);
        photoImageViews[slot].setVisibility(android.view.View.VISIBLE);

        if (tvRef != null) {
            tvRef.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> tvRef.setVisibility(android.view.View.GONE))
                    .start();
        }

        photoImageViews[slot].animate()
                .alpha(1f)
                .setDuration(200)
                .start();
    }

    public void resumePreviewFromPhotos() {
        mainHandler.post(() -> {
            Log.d(TAG, "resumePreviewFromPhotos, isStopped=" + isStopped.get());

            if (isStopped.get()) {
                Log.w(TAG, "Camera is already stopped, skipping resumePreviewFromPhotos");
                return;
            }

            if (photoImageViews != null) {
                for (int i = 0; i < photoImageViews.length; i++) {
                    if (photoImageViews[i] == null) continue;

                    final int slot = i;
                    photoImageViews[i].animate()
                            .alpha(0f)
                            .setDuration(150)
                            .withEndAction(() -> {
                                if (photoImageViews != null && photoImageViews[slot] != null) {
                                    photoImageViews[slot].setVisibility(android.view.View.GONE);
                                    photoImageViews[slot].setImageBitmap(null);
                                }
                            })
                            .start();

                    if (textureViews != null && textureViews[i] != null) {
                        textureViews[i].setAlpha(0f);
                        textureViews[i].setVisibility(android.view.View.VISIBLE);
                        textureViews[i].animate()
                                .alpha(1f)
                                .setDuration(150)
                                .start();
                    }
                }
            } else if (textureViews != null) {
                for (int i = 0; i < textureViews.length; i++) {
                    if (textureViews[i] != null) {
                        textureViews[i].setAlpha(1f);
                        textureViews[i].setVisibility(android.view.View.VISIBLE);
                    }
                }
            }

            if (sessions != null) {
                for (int i = 0; i < sessions.length; i++) {
                    Camera2Session session = sessions[i];
                    if (session == null) continue;

                    TextureView tv = (textureViews != null) ? textureViews[i] : null;
                    if (tv != null && tv.getSurfaceTexture() != null) {
                        SurfaceTexture st = tv.getSurfaceTexture();
                        Size preview = session.getPreviewSize();
                        st.setDefaultBufferSize(preview.getWidth(), preview.getHeight());
                        Log.d(TAG, "Restarting session " + i);
                        session.restartPreviewWithExistingSurface(st, buildSessionCallback(i));
                    } else {
                        Log.w(TAG, "Cannot restart session " + i + ": no TextureView or SurfaceTexture");
                        session.resumePreview();
                    }
                }
            }
        });
    }

    private void shutdownSessions() {
        if (sessions != null) {
            for (Camera2Session session : sessions) {
                if (session != null) {
                    session.shutdown();
                }
            }
            sessions = null;
        }
    }

    private void clearTextureViewListeners() {
        if (textureViews != null) {
            for (TextureView tv : textureViews) {
                if (tv != null) {
                    tv.setSurfaceTextureListener(null);
                }
            }
        }
    }

    public void stopPreview() {
        isStopped.set(true);
        mainHandler.post(() -> {
            shutdownSessions();
            isCapturing.set(false);

            if (containerView != null && context instanceof android.app.Activity) {
                ViewGroup rootView = (ViewGroup) ((android.app.Activity) context)
                        .getWindow().getDecorView().findViewById(android.R.id.content);
                if (rootView != null) {
                    rootView.removeView(containerView);
                }
                containerView = null;
            }

            // 卸掉 listener，避免复用 rootView 时旧 listener 触发回调读已 null 的 sessions[]
            clearTextureViewListeners();
            textureViews = null;
            photoImageViews = null;
            Log.d(TAG, "Preview stopped, camera resources and views released");
        });
    }

    public void pausePreview() {
        mainHandler.post(() -> {
            if (sessions == null || isStopped.get()) return;
            for (Camera2Session session : sessions) {
                if (session != null) {
                    session.pausePreview();
                }
            }
            Log.d(TAG, "Preview paused");
        });
    }

    public void resumePreview() {
        mainHandler.post(() -> {
            if (sessions == null || isStopped.get()) return;
            for (Camera2Session session : sessions) {
                if (session != null) {
                    session.resumePreview();
                }
            }
            Log.d(TAG, "Preview resumed");
        });
    }

    public void shutdown() {
        isStopped.set(true);
        mainHandler.post(() -> {
            shutdownSessions();
            clearTextureViewListeners();
            textureViews = null;
            photoImageViews = null;
            Log.d(TAG, "Shutdown complete");
        });
    }

    public TextureView[] getTextureViews() {
        return textureViews;
    }

    public ViewGroup getContainerView() {
        return containerView;
    }

    public android.graphics.Rect[] getPreviewRects() {
        if (containerView == null) return null;
        int[] location = new int[2];
        containerView.getLocationOnScreen(location);

        android.graphics.Rect[] rects = new android.graphics.Rect[slotCount];
        for (int i = 0; i < slotCount; i++) {
            if (textureViews != null && textureViews[i] != null) {
                int[] tvLoc = new int[2];
                textureViews[i].getLocationOnScreen(tvLoc);
                rects[i] = new android.graphics.Rect(
                        tvLoc[0], tvLoc[1],
                        tvLoc[0] + textureViews[i].getWidth(),
                        tvLoc[1] + textureViews[i].getHeight());
            }
        }
        return rects;
    }

    private void resolvePendingCall(JSObject result) {
        if (pendingCall != null) {
            pendingCall.resolve(result);
            pendingCall = null;
        }
    }

    private void rejectPendingCall(String error) {
        if (pendingCall != null) {
            pendingCall.reject(error);
            pendingCall = null;
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }
}
