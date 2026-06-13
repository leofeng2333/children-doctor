package com.children.doctor.plugins.dualcamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class Camera2Session {

    private static final String TAG = "Camera2Session";
    private static final int MAX_CAPTURE_BUFFERS = 2;
    private static final int OPEN_RETRY_COUNT = 2;
    private static final long OPEN_RETRY_DELAY_MS = 500;

    private final Context context;
    private final HandlerThread cameraThread;
    private final Handler cameraHandler;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private ImageReader imageReader;

    private Surface previewSurface;
    private SurfaceTexture previewSurfaceTexture;
    private boolean surfaceOwnedBySession = false;

    private final AtomicBoolean isOpen = new AtomicBoolean(false);
    private final AtomicBoolean isCapturing = new AtomicBoolean(false);
    private final AtomicBoolean isPreviewActive = new AtomicBoolean(false);
    private volatile boolean isShutdown = false;

    private String cameraId;
    private final int lensFacing;
    private final int targetLensFacing;
    private final int sensorOrientation;
    private final Size previewSize;
    private final Size captureSize;

    private CaptureCallback captureCallback;

    private static final Size DEFAULT_PREVIEW_SIZE = new Size(640, 480);
    private static final Size DEFAULT_CAPTURE_SIZE = new Size(1280, 960);

    public interface CaptureCallback {
        void onCaptureSuccess(String filePath, long fileSizeKb);
        void onCaptureError(String error);
    }

    public interface Callback {
        void onOpened();
        void onDisconnected();
        void onError(String error);
    }

    public static class Camera2Info {
        public final String cameraId;
        public final int lensFacing;
        public final Size previewSize;
        public final Size captureSize;

        public Camera2Info(String cameraId, int lensFacing, Size previewSize, Size captureSize) {
            this.cameraId = cameraId;
            this.lensFacing = lensFacing;
            this.previewSize = previewSize;
            this.captureSize = captureSize;
        }
    }

    public Camera2Session(Context context, String cameraId, int lensFacing, Size previewSize, Size captureSize) {
        this.context = context;
        this.cameraId = cameraId;
        this.targetLensFacing = lensFacing;
        this.lensFacing = lensFacing;
        this.previewSize = (previewSize != null && previewSize.getWidth() > 0 && previewSize.getHeight() > 0)
                ? previewSize : DEFAULT_PREVIEW_SIZE;
        this.captureSize = (captureSize != null && captureSize.getWidth() > 0 && captureSize.getHeight() > 0)
                ? captureSize : DEFAULT_CAPTURE_SIZE;

        this.sensorOrientation = readSensorOrientation();

        this.cameraThread = new HandlerThread("Camera2Thread-" + cameraId);
        this.cameraThread.start();
        this.cameraHandler = new Handler(cameraThread.getLooper());

        Log.d(TAG, "Created session for camera " + cameraId + ", lensFacing=" + lensFacing
                + ", sensorOrientation=" + sensorOrientation
                + ", preview=" + this.previewSize + ", capture=" + this.captureSize);
    }

    private int readSensorOrientation() {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            Integer orientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            return (orientation != null) ? orientation : 0;
        } catch (Exception e) {
            Log.w(TAG, "Failed to read sensor orientation", e);
            return 0;
        }
    }

    public static List<Camera2Info> getAvailableCameras(Context context) {
        List<Camera2Info> result = new ArrayList<>();
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            for (String id : manager.getCameraIdList()) {
                try {
                    CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                    Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                    int lensFacing = (facing != null) ? facing : CameraCharacteristics.LENS_FACING_BACK;

                    Size capture = chooseOptimalSize(context, id, true);
                    Size preview = chooseOptimalSize(context, id, false);

                    result.add(new Camera2Info(id, lensFacing, preview, capture));
                    Log.d(TAG, "Camera2Enum: id=" + id + ", facing=" + lensFacing
                            + ", preview=" + preview + ", capture=" + capture);
                } catch (Exception e) {
                    Log.w(TAG, "Skipping camera id=" + id + ": " + e.getMessage());
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to enumerate cameras", e);
        }
        return result;
    }

    private String findCameraIdByLensFacing(int facing) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            for (String id : manager.getCameraIdList()) {
                try {
                    CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                    Integer f = chars.get(CameraCharacteristics.LENS_FACING);
                    int lensFacing = (f != null) ? f : CameraCharacteristics.LENS_FACING_BACK;
                    if (lensFacing == facing) {
                        Log.d(TAG, "findCameraIdByLensFacing: found " + id + " for lensFacing=" + facing);
                        return id;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "getCameraCharacteristics failed for id=" + id + ", skipping: " + e.getMessage());
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to enumerate cameras", e);
        }
        return null;
    }

    private static Size chooseOptimalSize(Context ctx, String cameraId, boolean forCapture) {
        try {
            CameraManager manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            android.hardware.camera2.params.StreamConfigurationMap map =
                    chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (map == null) {
                return forCapture ? DEFAULT_CAPTURE_SIZE : DEFAULT_PREVIEW_SIZE;
            }

            Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
            Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);

            if (forCapture) {
                if (jpegSizes == null || jpegSizes.length == 0) {
                    return DEFAULT_CAPTURE_SIZE;
                }
                List<Size> candidates = new ArrayList<>();
                for (Size s : jpegSizes) {
                    if (s.getWidth() <= 1920 && s.getHeight() <= 1920) {
                        candidates.add(s);
                    }
                }
                if (candidates.isEmpty()) {
                    return DEFAULT_CAPTURE_SIZE;
                }
                return Collections.max(candidates, new CompareSizesByArea());
            } else {
                if (previewSizes == null || previewSizes.length == 0) {
                    return DEFAULT_PREVIEW_SIZE;
                }
                Size target = new Size(640, 480);
                Size result = previewSizes[0];
                long minDiff = Math.abs(result.getWidth() - target.getWidth())
                        + Math.abs(result.getHeight() - target.getHeight());
                for (Size s : previewSizes) {
                    if (s.getWidth() <= 1280 && s.getHeight() <= 1280) {
                        long diff = Math.abs(s.getWidth() - target.getWidth())
                                + Math.abs(s.getHeight() - target.getHeight());
                        if (diff < minDiff) {
                            minDiff = diff;
                            result = s;
                        }
                    }
                }
                return result;
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "chooseOptimalSize failed for " + cameraId, e);
            return forCapture ? DEFAULT_CAPTURE_SIZE : DEFAULT_PREVIEW_SIZE;
        }
    }

    public void open(Surface previewSurface, SurfaceTexture surfaceTexture, Callback callback) {
        if (isOpen.get()) {
            callback.onError("Camera already open");
            return;
        }

        this.previewSurface = previewSurface;
        this.previewSurfaceTexture = surfaceTexture;
        this.surfaceOwnedBySession = false;
        this.imageReader = ImageReader.newInstance(
                captureSize.getWidth(),
                captureSize.getHeight(),
                ImageFormat.JPEG,
                MAX_CAPTURE_BUFFERS
        );

        openWithRetryImpl(cameraId, previewSurface, 0, callback);
    }

    private void openWithRetryImpl(String idToOpen, Surface previewSurface, int attempt, Callback callback) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            manager.openCamera(idToOpen, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    if (isShutdown) {
                        Log.w(TAG, "Session was shutdown before camera opened");
                        camera.close();
                        return;
                    }
                    cameraId = idToOpen;
                    cameraDevice = camera;
                    createCaptureSession(callback);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    isOpen.set(false);
                    Log.w(TAG, "Camera " + idToOpen + " disconnected");
                    callback.onDisconnected();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    isOpen.set(false);
                    Log.e(TAG, "Camera " + idToOpen + " error: " + error + ", attempt=" + attempt);

                    if (attempt < OPEN_RETRY_COUNT) {
                        Log.d(TAG, "Retrying open for camera " + idToOpen + " (attempt " + (attempt + 1) + "/" + (OPEN_RETRY_COUNT + 1) + ")");
                        String nextId = findCameraIdByLensFacing(targetLensFacing);
                        if (nextId != null && !nextId.equals(idToOpen)) {
                            Log.d(TAG, "Re-enumerating, found new ID: " + nextId);
                        }
                        String idToUse = (nextId != null) ? nextId : idToOpen;
                        final String finalId = idToUse;
                        cameraHandler.postDelayed(() -> openWithRetryImpl(finalId, previewSurface, attempt + 1, callback),
                                OPEN_RETRY_DELAY_MS);
                    } else {
                        callback.onError("Camera error: " + error);
                    }
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "Failed to open camera " + idToOpen, e);
            String nextId = findCameraIdByLensFacing(targetLensFacing);
            if (nextId != null && !nextId.equals(idToOpen)) {
                Log.d(TAG, "Re-enumerating camera, found new ID: " + nextId + ", retrying...");
                cameraHandler.postDelayed(() -> openWithRetryImpl(nextId, previewSurface, attempt + 1, callback), OPEN_RETRY_DELAY_MS);
            } else if (attempt < OPEN_RETRY_COUNT) {
                Log.d(TAG, "Retrying open after exception for camera " + idToOpen);
                cameraHandler.postDelayed(() -> openWithRetryImpl(idToOpen, previewSurface, attempt + 1, callback),
                        OPEN_RETRY_DELAY_MS);
            } else {
                callback.onError("Failed to open camera: " + e.getMessage());
            }
        }
    }

    private void createCaptureSession(Callback callback) {
        try {
            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
            surfaces.add(imageReader.getSurface());

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "Session onConfigured: cameraDevice=" + cameraDevice);
                    if (isShutdown) {
                        Log.w(TAG, "Session shutdown during onConfigured, dropping session");
                        try { session.close(); } catch (Exception ignored) {}
                        return;
                    }
                    if (cameraDevice == null) {
                        Log.w(TAG, "CameraDevice already closed when session configured");
                        try { session.close(); } catch (Exception ignored) {}
                        callback.onError("Camera closed before session configured");
                        return;
                    }
                    captureSession = session;
                    startPreview();
                    isOpen.set(true);
                    callback.onOpened();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    captureSession = null;
                    callback.onError("Session configuration failed");
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to create capture session", e);
            callback.onError("Failed to create session: " + e.getMessage());
        }
    }

    private void startPreview() {
        if (isShutdown) {
            Log.w(TAG, "startPreview: session is shutdown, skipping");
            return;
        }
        if (captureSession == null || cameraDevice == null) {
            Log.w(TAG, "startPreview: session=" + captureSession + ", device=" + cameraDevice);
            return;
        }
        if (!isPreviewActive.compareAndSet(false, true)) {
            Log.d(TAG, "startPreview: preview already active");
            return;
        }

        try {
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(previewSurface);

            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

            captureSession.setRepeatingRequest(previewRequestBuilder.build(),
                    null, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "Failed to start preview", e);
            isPreviewActive.set(false);
        }
    }

    public void pausePreview() {
        if (!isPreviewActive.get()) return;
        try {
            captureSession.stopRepeating();
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to pause preview", e);
        }
        isPreviewActive.set(false);
    }

    public void resumePreview() {
        if (!isOpen.get() || isPreviewActive.get() || isShutdown) return;
        startPreview();
    }

    public void restartPreviewWithExistingSurface(SurfaceTexture surfaceTexture, Callback callback) {
        if (isShutdown) {
            Log.w(TAG, "Session is shutdown, skipping restartPreviewWithExistingSurface");
            return;
        }
        closeCaptureSession();
        isOpen.set(false);
        isCapturing.set(false);
        isPreviewActive.set(false);

        previewSurfaceTexture = surfaceTexture;
        previewSurface = new Surface(surfaceTexture);
        surfaceOwnedBySession = true;
        this.imageReader = ImageReader.newInstance(
                captureSize.getWidth(),
                captureSize.getHeight(),
                ImageFormat.JPEG,
                MAX_CAPTURE_BUFFERS
        );

        openWithRetryImpl(cameraId, previewSurface, 0, callback);
    }

    private void closeCaptureSession() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing capture session", e);
        }
        try {
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing camera device", e);
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    public void capture(String filePath, CountDownLatch latch, CaptureCallback callback) {
        if (!isOpen.get() || captureSession == null || cameraDevice == null) {
            callback.onCaptureError("Camera not ready");
            latch.countDown();
            return;
        }
        if (!isCapturing.compareAndSet(false, true)) {
            callback.onCaptureError("Capture already in progress");
            latch.countDown();
            return;
        }

        this.captureCallback = callback;

        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                Image image = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image == null) {
                        isCapturing.set(false);
                        callback.onCaptureError("No image available");
                        return;
                    }

                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);

                    byte[] finalBytes = applyFrontMirrorIfNeeded(bytes);

                    try (FileOutputStream fos = new FileOutputStream(filePath)) {
                        fos.write(finalBytes);
                    }

                    long fileSizeKb = new File(filePath).length() / 1024;
                    isCapturing.set(false);
                    callback.onCaptureSuccess(filePath, fileSizeKb);
                } catch (Exception e) {
                    Log.e(TAG, "Capture processing failed", e);
                    isCapturing.set(false);
                    callback.onCaptureError("Processing failed: " + e.getMessage());
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
            }
        }, cameraHandler);

        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            builder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(getDisplayRotation()));

            captureSession.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    Log.d(TAG, "Capture completed for " + cameraId);
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Capture failed", e);
            isCapturing.set(false);
            callback.onCaptureError("Capture failed: " + e.getMessage());
        }
    }

    private byte[] applyFrontMirrorIfNeeded(byte[] bytes) {
        if (lensFacing != CameraCharacteristics.LENS_FACING_FRONT) {
            return bytes;
        }

        Bitmap original = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (original == null) {
            return bytes;
        }

        int w = original.getWidth();
        int h = original.getHeight();
        Bitmap mirrored = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(mirrored);
        Matrix m = new Matrix();
        m.setScale(-1f, 1f);
        m.postTranslate(w, 0);
        canvas.drawBitmap(original, m, null);
        original.recycle();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        mirrored.compress(Bitmap.CompressFormat.JPEG, 95, baos);
        mirrored.recycle();
        return baos.toByteArray();
    }

    public void close() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing capture session", e);
        }

        try {
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing camera device", e);
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (previewSurface != null && surfaceOwnedBySession) {
            previewSurface.release();
            previewSurface = null;
        }

        if (previewSurfaceTexture != null && surfaceOwnedBySession) {
            previewSurfaceTexture.release();
            previewSurfaceTexture = null;
        }

        isOpen.set(false);
        isCapturing.set(false);
        isPreviewActive.set(false);
        isShutdown = true;
    }

    public void shutdown() {
        isShutdown = true;
        close();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Thread join interrupted", e);
            }
            if (cameraThread.isAlive()) {
                cameraThread.quit();
                try {
                    cameraThread.join(500);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Forced thread join interrupted", e);
                }
                Log.w(TAG, "Forced thread quit for camera " + cameraId);
            }
        }
    }

    public boolean isOpen() {
        return isOpen.get();
    }

    public Size getPreviewSize() {
        return previewSize;
    }

    public Size getCaptureSize() {
        return captureSize;
    }

    public String getCameraId() {
        return cameraId;
    }

    public int getLensFacing() {
        return lensFacing;
    }

    public int getSensorOrientation() {
        return sensorOrientation;
    }

    public int getDisplayRotation() {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        return display.getRotation();
    }

    public int getJpegOrientation(int displayRotation) {
        int rotation;
        switch (displayRotation) {
            case Surface.ROTATION_90:  rotation = 90;  break;
            case Surface.ROTATION_180: rotation = 180; break;
            case Surface.ROTATION_270: rotation = 270; break;
            default:                    rotation = 0;   break;
        }

        if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            return (sensorOrientation + rotation) % 360;
        } else {
            return (sensorOrientation - rotation + 360) % 360;
        }
    }

    private static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight()
                    - (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}
