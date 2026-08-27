package com.children.doctor.plugins.dualcamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
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
    private android.widget.FrameLayout containerView;
    /** 每列的对焦辅助椭圆虚线框（索引对齐 slot）。仅在 native 预览开启时显示。 */
    private android.view.View[] focusOverlays;
    /** 是否已为该 slot 应用过 preview transform（首帧触发后置 true）。 */
    private boolean[] transformAppliedForSlot;
    /** 设备旋转监听，displayRotation 变化时重算 preview transform。 */
    private android.view.OrientationEventListener orientationListener;
    /** 上一次报告的旋转角度（去重，只在跨越 0/90/180/270 边界时触发）。 */
    private int lastReportedRotation = -1;
    /** 贴在预览下沿的跨列引导文案 TextView，仅有一个（slotCount>=1 时都显示）。 */
    private android.widget.TextView focusCaption;
    /** 单/双列预览画面纹理高（4:3 高度），caption 位置计算用。 */
    private int previewHeightPx;
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
            // 硬编码方向校准（目标机器实测）：
            //   slot=0（左）：预览顺时针偏90° → 需补逆时针90° → extraRotate=270
            //   slot=1（右）：预览逆时针偏180° → 需补顺时针180° → extraRotate=180
            if (i == 0) {
                sessions[i].setCalibration(270, false);
                log("CALIBRATION slot=0 extraRotate=270 extraMirror=false");
            } else if (i == 1) {
                sessions[i].setCalibration(90, false);
                log("CALIBRATION slot=1 extraRotate=180 extraMirror=false");
            }
        }

        isStopped.set(false);
        openedCount = 0;
        failedCount = 0;
        slotErrors = new String[slotCount];
        pendingCall = call;
        buildTextureViews(rootView, slotCount);
    }

    private void buildTextureViews(ViewGroup rootView, int slotCount) {
        // 修订 D：防止上次 preview 残留字段穿到新一次预览（虽然 stopPreview 会清理，双保险）
        focusOverlays = new android.view.View[slotCount];
        focusCaption = null;
        transformAppliedForSlot = new boolean[slotCount];

        android.widget.FrameLayout container = new android.widget.FrameLayout(context);
        container.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
                    log("SurfaceTexture available slot=" + slot + " viewSize=" + width + "x" + height);
                    if (sessions == null || sessions[slot] == null) return;

                    Size preview = sessions[slot].getPreviewSize();
                    st.setDefaultBufferSize(preview.getWidth(), preview.getHeight());
                    Log.d(TAG, "setDefaultBufferSize: " + preview.getWidth() + "x" + preview.getHeight());
                    log("setDefaultBufferSize slot=" + slot + " preview=" + preview.getWidth() + "x" + preview.getHeight());
                    surfaceTexture = st;
                    surface = new Surface(surfaceTexture);
                    mainHandler.post(this::openCameraIfReady);
                    // 首次 layout 后立即计算并应用一次预览 transform
                    mainHandler.post(() -> configureTransform(slot, "onSurfaceTextureAvailable"));
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int width, int height) {
                    Log.d(TAG, "SurfaceTexture sizeChanged: " + width + "x" + height + ", slot=" + slot);
                    log("SurfaceTexture sizeChanged slot=" + slot + " viewSize=" + width + "x" + height);
                    configureTransform(slot, "onSurfaceTextureSizeChanged");
                }

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
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {
                    // 首帧到位后再补一次 transform（onSurfaceTextureAvailable 时 view 可能还没 layout 完成）
                    if (!transformAppliedForSlot[slot]) {
                        transformAppliedForSlot[slot] = true;
                        configureTransform(slot, "onSurfaceTextureUpdated(首帧)");
                    }
                }
            });

            ViewGroup root = buildCameraSlotView(slot, textureView, colMargin);
            container.addView(root);
        }

        // 设备旋转时 displayRotation 会变，重新计算每个 slot 的 preview transform
        enableOrientationListener();

        // 计算单列宽（与 buildCameraSlotView 内部 0.85/0.415 严格一致），在 caption 创建前
        // 算出来供后续 OnLayout 阶段做精确底部偏移。
        int colW = slotCount == 1
                ? (int) (screenWidthPx * 0.85f)
                : (int) (screenWidthPx * 0.415f);
        previewHeightPx = (int) (colW * 4f / 3f);

        // 跨列引导文案（单预览/双预览都用同一个；详见设计文档 §3.5.3）
        //
        // 坑：previewWrapper 高度 = label(32) + textureView(h) + previewWrapper上下padding(24)，
        // 但 FrameLayout 是 MATCH_PARENT，col 用 Gravity.CENTER_VERTICAL 时 col 上下留白
        // 大小依赖容器实际高度。硬算 topMargin 会因为 container 可能被子元素挤压 / 顶部有
        // status bar 影响而不准。这里改用：Gravity.BOTTOM + 在首次 layout 后取
        // previewWrapper.getBottom() 反算精确 bottomMargin。
        focusCaption = new android.widget.TextView(context);
        focusCaption.setText("请保证自己的面部与虚线区域大小尽量吻合");
        focusCaption.setTextSize(14);
        focusCaption.setTextColor(0xFF1A1A1A);
        focusCaption.setBackgroundColor(0x00000000); // 透明
        focusCaption.setSingleLine(true);
        focusCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.widget.FrameLayout.LayoutParams capParams =
                new android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        // 调试期：caption 相对设备底部的百分比距离（0.0 = 贴底，1.0 = 屏顶）
        // 0.3 = 距设备底部 30% 屏高处。先用这个手调出合适位置，
        // 之后再决定要不要换回"贴 preview 底 + 13dp"。
        final float bottomRatio = 0.3f;
        capParams.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
        capParams.leftMargin = dpToPx(24);
        capParams.rightMargin = dpToPx(24);
        // 兜底：取一个略大于预览 + 相机按钮区的高，避开屏底按钮
        capParams.bottomMargin = dpToPx(160);
        focusCaption.setLayoutParams(capParams);
        container.addView(focusCaption);
        rootView.addView(container);

        // 一次性把 caption 定位到距屏底 bottomRatio 高度处。
        // post 到主线程队尾：此时 rootView/container 已 layout 完，getHeight() 是真实值。
        focusCaption.post(() -> {
            int containerH = container.getHeight();
            if (containerH == 0) return;
            float density = context.getResources().getDisplayMetrics().density;
            int wantedPx = (int) (containerH * bottomRatio);
            Log.d(TAG, "caption-percent: containerH=" + containerH
                    + " ratio=" + bottomRatio
                    + " wantedPx=" + wantedPx
                    + " wantedDp=" + (int)(wantedPx / density)
                    + " prevBottomMargin=" + ((android.widget.FrameLayout.LayoutParams) focusCaption.getLayoutParams()).bottomMargin);
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) focusCaption.getLayoutParams();
            lp.bottomMargin = wantedPx;
            focusCaption.setLayoutParams(lp);
        });
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

        // 对焦辅助椭圆虚线框（仅装饰）：MATCH_PARENT × MATCH_PARENT，与 textureView 同层
        // 但作为最上层的第三个 child，paint 只画 stroke，中央完全透明不挡画面
        FaceDetectionOverlay focusOverlay = new FaceDetectionOverlay(context);
        android.widget.FrameLayout.LayoutParams overlayParams =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        previewWrapper.addView(focusOverlay, overlayParams);
        // 字段由 buildTextureViews 创建为 focusOverlays[index] 数组占位
        if (focusOverlays != null && index < focusOverlays.length) {
            focusOverlays[index] = focusOverlay;
        }

        android.widget.LinearLayout col = new android.widget.LinearLayout(context);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setGravity(android.view.Gravity.CENTER);

        // 修订 A：container 是 FrameLayout，col 不再用 LinearLayout.LayoutParams，
        // 改用 FrameLayout.LayoutParams，并通过 gravity 区分 START / END，
        // 避免两列在 FrameLayout 下叠在同一坐标
        android.widget.FrameLayout.LayoutParams colParams =
                new android.widget.FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        colParams.gravity = slotCount == 1
                ? android.view.Gravity.CENTER
                : (index == 0
                        ? android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START
                        : android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.END);
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

                TextureView tv = (textureViews != null && slot < textureViews.length)
                        ? textureViews[slot] : null;
                Log.d(TAG, "onOpened: slot=" + slot + " tv=" + (tv != null ? tv.getWidth() + "x" + tv.getHeight() : "null"));
                if (tv != null) {
                    Log.d(TAG, "Camera slot " + slot + " preview started");
                    // 摄像头打开后，session 内的 sensorOrientation / previewSize 已就绪，
                    // 此时 viewbox 也已 layout 完成，再补一次 transform 兜底
                    configureTransform(slot, "session.onOpened");
                }

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

            // 修订 B：拍照确认时先同步隐藏对焦辅助与底部文案，避免 200ms 渐显期内
            // 椭圆线叠在拍照结果上。早于 photoView.post() 内的 animatePhotoTransition。
            if (focusOverlays != null) {
                for (android.view.View v : focusOverlays) {
                    if (v != null) v.setVisibility(android.view.View.GONE);
                }
            }
            if (focusCaption != null) {
                focusCaption.setVisibility(android.view.View.GONE);
            }

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

                    // 修订 C：椭圆与 caption 跟随 textureView 同步 150ms alpha 渐显，
                    // 避免"黑屏上先出现椭圆再填入画面"的违和感
                    if (focusOverlays != null && focusOverlays[i] != null) {
                        android.view.View overlay = focusOverlays[i];
                        overlay.setAlpha(0f);
                        overlay.setVisibility(android.view.View.VISIBLE);
                        overlay.animate().alpha(1f).setDuration(150).start();
                    }
                }
                // caption 整体渐显一次（跨列，仅一个 TextView）
                if (focusCaption != null) {
                    focusCaption.setAlpha(0f);
                    focusCaption.setVisibility(android.view.View.VISIBLE);
                    focusCaption.animate().alpha(1f).setDuration(150).start();
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
            // 内存卫生：release 引用（container 已 removeView，但字段仍持有，可能泄漏）
            focusOverlays = null;
            focusCaption = null;
            transformAppliedForSlot = null;
            disableOrientationListener();
            Log.d(TAG, "Preview stopped, camera resources and views released");
        });
    }

    /**
     * 设置指定 slot 摄像头的方向校准量。
     * 必须在 startPreview 之后、stopPreview 之前调用。
     * 已在运行的 configureTransform 会立即重算并应用，拍照路径在下一次 capture 时生效。
     * @param slot 槽位索引（0 或 1）
     * @param extraRotateDegrees 额外旋转角（0/90/180/270）
     * @param extraMirrorNeeded true=再补一次水平镜像
     */
    public void setSlotCalibration(int slot, int extraRotateDegrees, boolean extraMirrorNeeded) {
        mainHandler.post(() -> {
            if (sessions == null || slot < 0 || slot >= sessions.length) {
                Log.w(TAG, "setSlotCalibration: invalid slot " + slot);
                return;
            }
            Camera2Session s = sessions[slot];
            s.setCalibration(extraRotateDegrees, extraMirrorNeeded);
            // 立即重新触发 transform，重算会用上新的校准量
            configureTransform(slot, "calibrationChanged(slot=" + slot + ")");
            Log.d(TAG, "setSlotCalibration applied for slot=" + slot
                    + " extraRotate=" + s.getExtraRotate() + " extraMirror=" + s.getExtraMirror());
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
            focusOverlays = null;
            focusCaption = null;
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

    /**
     * 深度查找第一个在层级中匹配 "容器含 TextureView 子节点" 的 FrameLayout；
     * 用于 caption 锚定场景。返回 null 时调用方应跳过本次 layout 修正。
     */
    private android.view.View findPreviewWrapperDeep(android.view.View root) {
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            // 当前层先看：是否当前 vg 同时满足 "是 FrameLayout" 且 "子节点里至少有一个 TextureView"
            boolean hasTextureViewChild = false;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (vg.getChildAt(i) instanceof TextureView) {
                    hasTextureViewChild = true;
                    break;
                }
            }
            if (hasTextureViewChild && vg instanceof android.widget.FrameLayout) {
                return vg;
            }
            // 否则递归
            for (int i = 0; i < vg.getChildCount(); i++) {
                android.view.View found = findPreviewWrapperDeep(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
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

    /**
     * 计算并应用 TextureView 的 preview transform，让 UVC 摄像头预览方向正确。
     *
     * <p>公式来源：Android 官方 Camera2BasicFragment.configureTransform。
     * 关键变量（全部从 HAL 真实读取）：
     * <ul>
     *   <li>{@code previewSize}：{@link Camera2Session#getPreviewSize()}，UVC 通常是 1280x720 / 1920x1080（landscape）</li>
     *   <li>{@code sensorOrientation}：{@link Camera2Session#getSensorOrientation()}，UVC 常见 0/180；手机内置前置 270、后置 90</li>
     *   <li>{@code displayRotation}：{@link Camera2Session#getDisplayRotation()}，0/90/180/270 → 0/1/2/3</li>
     *   <li>{@code lensFacing}：影响 front camera 的额外水平镜像（HAL 层默认行为）</li>
     * </ul>
     *
     * <p>计算出的 rotation 直接写入 CaptureLogger，便于核对"自动识别"是否正确。
     * 该方法幂等：每次调用都用最新 view 尺寸重算并覆盖 setTransform。
     */
    private void configureTransform(int slot, String trigger) {
        if (textureViews == null || slot < 0 || slot >= textureViews.length) return;
        TextureView tv = textureViews[slot];
        if (tv == null) return;
        if (sessions == null || slot >= sessions.length || sessions[slot] == null) {
            log("configureTransform slot=" + slot + " trigger=" + trigger + " SKIP sessions null");
            return;
        }

        int vw = tv.getWidth();
        int vh = tv.getHeight();
        if (vw <= 0 || vh <= 0) {
            log("configureTransform slot=" + slot + " trigger=" + trigger
                    + " SKIP view not laid out view=" + vw + "x" + vh);
            return;
        }

        Camera2Session s = sessions[slot];
        Size preview = s.getPreviewSize();
        if (preview == null) {
            log("configureTransform slot=" + slot + " trigger=" + trigger + " SKIP previewSize null");
            return;
        }
        int sensor = s.getSensorOrientation();
        int displayRot = s.getDisplayRotation(); // Surface.ROTATION_0/90/180/270
        int lensFacing = s.getLensFacing();
        String facingStr;
        switch (lensFacing) {
            case CameraCharacteristics.LENS_FACING_FRONT:    facingStr = "FRONT"; break;
            case CameraCharacteristics.LENS_FACING_BACK:     facingStr = "BACK"; break;
            case CameraCharacteristics.LENS_FACING_EXTERNAL: facingStr = "EXTERNAL"; break;
            default:                                          facingStr = "UNKNOWN(" + lensFacing + ")";
        }

        // Camera2BasicFragment 公式：
        //   swappedDimensions = (sensor is 90/270) != (displayRot is 90/270)
        //   整体旋转 = (sensor - displayRotDeg + 360) % 360
        int displayRotDeg;
        switch (displayRot) {
            case Surface.ROTATION_90:  displayRotDeg = 90;  break;
            case Surface.ROTATION_180: displayRotDeg = 180; break;
            case Surface.ROTATION_270: displayRotDeg = 270; break;
            default:                    displayRotDeg = 0;   break;
        }
        boolean sensorIsRotated = (sensor == 90 || sensor == 270);
        boolean displayIsRotated = (displayRotDeg == 90 || displayRotDeg == 270);
        boolean swappedDimensions = sensorIsRotated != displayIsRotated;

        int rotation = (sensor - displayRotDeg + 360) % 360;

        // 目标 viewbox 尺寸：竖屏预览时取 view 的 w/h，landscape 时互换
        RectF viewRect = new RectF(0, 0, vw, vh);
        // 源预览尺寸：sensor 是横屏 0/180 时保持原样，是 90/270 时互换以得到"自然方向"
        RectF srcRect = new RectF(0, 0,
                swappedDimensions ? preview.getHeight() : preview.getWidth(),
                swappedDimensions ? preview.getWidth()  : preview.getHeight());

        Matrix matrix = new Matrix();
        // 把 srcRect 缩放到填满 viewRect（FILL = 等比放大填满，可能裁剪）
        matrix.setRectToRect(srcRect, viewRect, Matrix.ScaleToFit.FILL);

        // UVC 外接摄像头被 HAL 错报为 LENS_FACING_FRONT 时，与 EXTERNAL/BACK 同等处理：
        //   - 不做额外的水平镜像（系统相机不会自动镜像外接摄像头）；
        //   - 旋转公式与后置一致。
        // 这样预览 setTransform 与拍照 applyFrontMirrorIfNeeded 走完全相同的角度公式。
        boolean isExternal = (lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL);
        boolean isFront = (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) && !isExternal;

        // 前置摄像头 HAL 还会自动水平镜像预览，Matrix 需再补一个 -1 X scale 才能"看上去正常"
        if (isFront) {
            matrix.postScale(-1f, 1f, vw / 2f, vh / 2f);
        }

        // 整体旋转（绕中心）
        matrix.postRotate(rotation, vw / 2f, vh / 2f);

        // 摄像头校准偏移量（extraRotate / extraMirror）叠加在公式之上
        if (s.getExtraRotate() != 0) {
            matrix.postRotate(s.getExtraRotate(), vw / 2f, vh / 2f);
            log("configureTransform slot=" + slot + " extraRotate applied: " + s.getExtraRotate());
        }
        if (s.getExtraMirror()) {
            matrix.postScale(-1f, 1f, vw / 2f, vh / 2f);
            log("configureTransform slot=" + slot + " extraMirror applied");
        }

        tv.setTransform(matrix);

        log("configureTransform slot=" + slot + " trigger=" + trigger
                + " | facing=" + facingStr
                + " sensorOrient=" + sensor
                + " displayRot=" + displayRotDeg + "(0)"
                + " rotation=" + rotation + "+extraRotate=" + s.getExtraRotate()
                + " swapped=" + swappedDimensions
                + " | preview=" + preview.getWidth() + "x" + preview.getHeight()
                + " view=" + vw + "x" + vh
                + " → srcRect=" + (int) srcRect.width() + "x" + (int) srcRect.height()
                + " extraMirror=" + s.getExtraMirror()
                + " applied");

        Log.d(TAG, "configureTransform slot=" + slot + " facing=" + facingStr
                + " sensor=" + sensor + " displayRot=" + displayRotDeg
                + " rotation=" + rotation + "+extraRotate=" + s.getExtraRotate()
                + " swapped=" + swappedDimensions
                + " preview=" + preview.getWidth() + "x" + preview.getHeight()
                + " view=" + vw + "x" + vh + " extraMirror=" + s.getExtraMirror()
                + " trigger=" + trigger);
    }

    /**
     * 启动设备旋转监听。displayRot 跨越 0/90/180/270 边界时，对所有 slot 重算 transform。
     * 该方法幂等，重复调用只启动一次。
     */
    private void enableOrientationListener() {
        if (orientationListener != null) return;
        orientationListener = new android.view.OrientationEventListener(context) {
            @Override
            public void onOrientationChanged(int orientationDeg) {
                if (orientationDeg == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return;
                // 转成 0/90/180/270
                int snapped = ((orientationDeg + 45) / 90) * 90 % 360;
                if (snapped == lastReportedRotation) return;
                lastReportedRotation = snapped;
                log("OrientationEventListener rotation=" + snapped);
                // onOrientationChanged 跑在 sensor 线程，转到主线程再 setTransform
                mainHandler.post(() -> {
                    if (sessions == null || textureViews == null) return;
                    for (int i = 0; i < textureViews.length; i++) {
                        configureTransform(i, "OrientationEvent(" + snapped + ")");
                    }
                });
            }
        };
        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable();
            log("OrientationEventListener enabled");
        } else {
            log("OrientationEventListener canDetectOrientation=false (device likely doesn't support), skipped");
            orientationListener = null;
        }
    }

    /** 关闭旋转监听。stopPreview 中调用。 */
    private void disableOrientationListener() {
        if (orientationListener != null) {
            orientationListener.disable();
            orientationListener = null;
            lastReportedRotation = -1;
            log("OrientationEventListener disabled");
        }
    }
}
