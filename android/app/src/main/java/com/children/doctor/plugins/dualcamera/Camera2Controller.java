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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Camera2Controller {

    private static final String TAG = "Camera2Controller";

    private final Context context;
    private final Handler mainHandler;

    private TextureView[] textureViews;
    private android.widget.ImageView[] photoImageViews;
    private Camera2Session[] sessions;
    private android.widget.LinearLayout containerView;
    private int slotCount;

    private final AtomicBoolean isCapturing = new AtomicBoolean(false);
    private boolean isPhotoDisplayed = false;

    private final String[] labels = {"正视图", "右侧视图"};
    private static final String PHOTO_DIR_NAME = "dual_camera_photos";

    public interface PreviewCallback {
        void onError(String error);
        void onCaptureComplete(String[] uris, String[] paths, long[] fileSizeKb);
    }

    private PreviewCallback callback;

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

        int slotCount = Math.min(cameras.size(), 2);
        textureViews = new TextureView[slotCount];
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

        openedCount = 0;
        this.callReference = call;
        buildTextureViews(rootView, slotCount);
    }

    private int openedCount = 0;
    private PluginCall callReference;

    private void buildTextureViews(ViewGroup rootView, int slotCount) {
        android.widget.LinearLayout container = new android.widget.LinearLayout(context);
        container.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        container.setGravity(android.view.Gravity.CENTER);

        if (slotCount == 1) {
            container.setOrientation(android.widget.LinearLayout.VERTICAL);
        } else {
            container.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        }

        this.containerView = container;
        this.photoImageViews = new android.widget.ImageView[slotCount];

        int screenWidthPx = context.getResources().getDisplayMetrics().widthPixels;
        int colMargin = dpToPx(16);

        for (int i = 0; i < slotCount; i++) {
            TextureView tv = new TextureView(context);
            textureViews[i] = tv;

            final int slot = i;

            tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                private android.view.Surface surface;

                private void openCameraIfReady() {
                    if (surface == null || sessions[slot] == null) return;

                    Camera2Session session = sessions[slot];
                    session.getPreviewSize(); // ensure session is valid

                    session.open(surface, new Camera2Session.Callback() {
                        @Override
                        public void onOpened() {
                            synchronized (Camera2Controller.this) {
                                openedCount++;
                                if (openedCount == slotCount) {
                                    onAllCamerasOpened();
                                }
                            }
                        }

                        @Override
                        public void onError(String error) {
                            synchronized (Camera2Controller.this) {
                                Log.e(TAG, "Camera " + slot + " open error: " + error);
                                openedCount++;
                                if (openedCount == slotCount) {
                                    callReference.reject("Failed to open cameras: " + error);
                                }
                            }
                        }
                    });
                }

                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
                    Log.d(TAG, "SurfaceTexture available: " + width + "x" + height + ", slot=" + slot);
                    Camera2Session session = sessions[slot];
                    if (session == null) return;

                    surfaceTexture.setDefaultBufferSize(
                            session.getPreviewSize().getHeight(), session.getPreviewSize().getWidth());
                    // Use TextureView's owned Surface, not new Surface(texture).
                    surface = new android.view.Surface(surfaceTexture);

                    // Post to main thread to avoid opening multiple cameras simultaneously.
                    mainHandler.post(() -> openCameraIfReady());
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {}

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
                    if (sessions != null && sessions[slot] != null) {
                        sessions[slot].close();
                    }
                    if (surface != null) {
                        surface.release();
                        surface = null;
                    }
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {}
            });

            android.widget.TextView label = new android.widget.TextView(context);
            label.setText(labels[i]);
            label.setTextSize(14);
            label.setTextColor(0xFF333333);
            label.setGravity(android.view.Gravity.CENTER);
            label.setPadding(0, dpToPx(4), 0, dpToPx(8));

            int w = slotCount == 1 ? (int) (screenWidthPx * 0.85f)
                                   : (int) (screenWidthPx * 0.415f);
            int h = (int) (w * 4f / 3f);

            android.widget.LinearLayout col = new android.widget.LinearLayout(context);
            col.setOrientation(android.widget.LinearLayout.VERTICAL);
            col.setGravity(android.view.Gravity.CENTER);

            android.widget.LinearLayout.LayoutParams colParams =
                    new android.widget.LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
            int marginStart = slotCount == 1 ? colMargin
                                : (i == 0 ? colMargin : colMargin / 2);
            int marginEnd = slotCount == 1 ? colMargin
                                : (i == slotCount - 1 ? colMargin : colMargin / 2);
            colParams.setMargins(marginStart, 0, marginEnd, 0);
            col.setLayoutParams(colParams);

            android.widget.FrameLayout.LayoutParams tvParams =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, h);
            tvParams.setMargins(0, dpToPx(12), 0, dpToPx(12));
            tv.setLayoutParams(tvParams);
            tv.setVisibility(android.view.View.VISIBLE);

            android.widget.ImageView photoView = new android.widget.ImageView(context);
            photoImageViews[i] = photoView;
            android.widget.FrameLayout.LayoutParams photoParams =
                    new android.widget.FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, h);
            photoParams.setMargins(0, dpToPx(12), 0, dpToPx(12));
            photoView.setLayoutParams(photoParams);
            photoView.setVisibility(android.view.View.GONE);

            label.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            android.widget.FrameLayout previewWrapper = new android.widget.FrameLayout(context);
            previewWrapper.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, h + dpToPx(24)));
            previewWrapper.addView(tv);
            previewWrapper.addView(photoView);

            col.addView(label);
            col.addView(previewWrapper);
            container.addView(col);
        }

        rootView.addView(container);
    }

    private void onAllCamerasOpened() {
        mainHandler.post(() -> {
            try {
                JSArray camList = new JSArray();
                for (int i = 0; i < sessions.length; i++) {
                    JSObject camJson = new JSObject();
                    camJson.put("cameraId", sessions[i].getCameraId());
                    camJson.put("lensFacing", sessions[i].getLensFacing());
                    camList.put(camJson);
                }
                JSObject ret = new JSObject();
                ret.put("cameras", camList);
                ret.put("concurrent", sessions.length >= 2);
                callReference.resolve(ret);
            } catch (Exception e) {
                callReference.reject("Failed to build result", e);
            }
        });
    }

    public void capture(CaptureResultCallback resultCallback) {
        if (isCapturing.get()) {
            resultCallback.onError("Capture already in progress");
            return;
        }
        if (sessions == null || sessions.length == 0) {
            resultCallback.onError("Camera not initialized");
            return;
        }

        isCapturing.set(true);

        File photoDir = new File(context.getCacheDir(), PHOTO_DIR_NAME);
        if (!photoDir.exists()) {
            photoDir.mkdirs();
        }

        String timestamp = String.valueOf(System.currentTimeMillis());
        String[] captureLabels = {"front", "back"};
        String[] filePaths = new String[sessions.length];

        for (int i = 0; i < sessions.length; i++) {
            filePaths[i] = new File(photoDir, captureLabels[i] + "_" + timestamp + ".jpg").getAbsolutePath();
        }

        CountDownLatch latch = new CountDownLatch(sessions.length);
        StringBuilder errors = new StringBuilder();
        String[] capturedPaths = new String[sessions.length];
        long[] capturedSizes = new long[sessions.length];

        for (int i = 0; i < sessions.length; i++) {
            final int slot = i;
            sessions[i].capture(filePaths[i], new Camera2Session.CaptureCallback() {
                @Override
                public void onCaptureSuccess(String filePath, long fileSizeKb) {
                    capturedPaths[slot] = filePath;
                    capturedSizes[slot] = fileSizeKb;
                    Log.d(TAG, "Slot " + slot + " captured: " + filePath + " (" + fileSizeKb + " KB)");
                    latch.countDown();
                }

                @Override
                public void onCaptureError(String error) {
                    synchronized (errors) {
                        errors.append(captureLabels[slot]).append(": ").append(error).append("; ");
                    }
                    latch.countDown();
                }
            });
        }

        new Thread(() -> {
            try {
                boolean completed = latch.await(15, TimeUnit.SECONDS);
                isCapturing.set(false);

                if (!completed) {
                    mainHandler.post(() -> resultCallback.onError("Capture timeout"));
                    return;
                }

                if (errors.length() > 0) {
                    mainHandler.post(() -> resultCallback.onError("Capture errors: " + errors));
                    return;
                }

                String[] uris = new String[sessions.length];
                for (int i = 0; i < sessions.length; i++) {
                    if (capturedPaths[i] != null) {
                        File f = new File(capturedPaths[i]);
                        uris[i] = FileProvider.getUriForFile(
                                context,
                                context.getPackageName() + ".fileprovider",
                                f
                        ).toString();
                    }
                }

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

    public interface CaptureResultCallback {
        void onSuccess(String[] uris, String[] paths, long[] fileSizeKb);
        void onError(String error);
    }

    public void stopPreview() {
        mainHandler.post(() -> {
            if (sessions != null) {
                for (Camera2Session session : sessions) {
                    if (session != null) {
                        session.shutdown();
                    }
                }
                sessions = null;
            }
            isCapturing.set(false);

            if (containerView != null) {
                ViewGroup rootView = (ViewGroup) ((android.app.Activity) context).getWindow().getDecorView().findViewById(android.R.id.content);
                if (rootView != null) {
                    rootView.removeView(containerView);
                }
                containerView = null;
            }

            Log.d(TAG, "Preview stopped, camera resources and views released");
        });
    }

    public void displayPhotos(String[] photoPaths) {
        if (isPhotoDisplayed) {
            Log.w(TAG, "displayPhotos: already displayed, ignoring duplicate call");
            return;
        }
        isPhotoDisplayed = true;

        mainHandler.post(() -> {
            if (photoImageViews == null || photoPaths == null) return;

            Log.d(TAG, "displayPhotos: " + photoImageViews.length + " slots");

            for (int i = 0; i < photoImageViews.length; i++) {
                if (photoImageViews[i] == null || photoPaths[i] == null) continue;

                boolean isFront = sessions != null && sessions[i] != null
                        && sessions[i].getLensFacing() == CameraCharacteristics.LENS_FACING_FRONT;

                Log.d(TAG, "Slot " + i + ": decoding " + photoPaths[i]);

                Bitmap rawBmp = android.graphics.BitmapFactory.decodeFile(photoPaths[i]);
                if (rawBmp == null) {
                    Log.e(TAG, "Slot " + i + ": BitmapFactory.decodeFile returned null");
                    continue;
                }

                Log.d(TAG, "Slot " + i + ": rawBitmap=" + rawBmp.getWidth() + "x" + rawBmp.getHeight()
                        + ", isFront=" + isFront);

                final int slot = i;
                final Bitmap bmpToShow = rawBmp;
                final TextureView tvRef = textureViews[i];
                final android.widget.ImageView photoView = photoImageViews[i];

                photoView.post(() -> {
                    photoView.setImageBitmap(bmpToShow);
                    photoView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                    showPhotoWithFade(slot, tvRef);
                });
            }

            // Shut down sessions AFTER posting the bitmap display tasks.
            if (sessions != null) {
                for (Camera2Session session : sessions) {
                    if (session != null) {
                        session.shutdown();
                    }
                }
                sessions = null;
            }

            Log.d(TAG, "displayPhotos done");
        });
    }

    private void showPhotoWithFade(int slot, TextureView tvRef) {
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

    public void shutdown() {
        mainHandler.post(() -> {
            if (sessions != null) {
                for (Camera2Session session : sessions) {
                    if (session != null) {
                        session.shutdown();
                    }
                }
                sessions = null;
            }
            textureViews = null;
            photoImageViews = null;
            Log.d(TAG, "Shutdown complete");
        });
    }

    public Camera2Session[] getSessions() {
        return sessions;
    }

    public TextureView[] getTextureViews() {
        return textureViews;
    }

    public android.graphics.Rect[] getPreviewRects() {
        if (containerView == null) return null;
        int[] location = new int[2];
        containerView.getLocationOnScreen(location);
        android.graphics.Rect containerRect = new android.graphics.Rect(
                location[0], location[1],
                location[0] + containerView.getWidth(),
                location[1] + containerView.getHeight());

        android.graphics.Rect[] rects = new android.graphics.Rect[slotCount];
        for (int i = 0; i < slotCount; i++) {
            if (textureViews[i] != null) {
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

    public ViewGroup getContainerView() {
        return containerView;
    }

    private int dpToPx(int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }
}
