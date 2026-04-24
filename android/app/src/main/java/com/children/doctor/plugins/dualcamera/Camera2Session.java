package com.children.doctor.plugins.dualcamera;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.view.Surface;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class Camera2Session {

    private static final String TAG = "Camera2Session";

    private final Context context;
    private final HandlerThread cameraThread;
    private final Handler cameraHandler;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private ImageReader imageReader;

    private Surface previewSurface;
    private SurfaceTexture previewSurfaceTexture;

    private final AtomicBoolean isOpen = new AtomicBoolean(false);
    private final AtomicBoolean isCapturing = new AtomicBoolean(false);

    private final String cameraId;
    private final int lensFacing;
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
        this.lensFacing = lensFacing;
        this.previewSize = (previewSize != null && previewSize.getWidth() > 0 && previewSize.getHeight() > 0)
                ? previewSize : DEFAULT_PREVIEW_SIZE;
        this.captureSize = (captureSize != null && captureSize.getWidth() > 0 && captureSize.getHeight() > 0)
                ? captureSize : DEFAULT_CAPTURE_SIZE;

        int so = 0;
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            Integer sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            so = (sensorOrientation != null) ? sensorOrientation : 0;
        } catch (Exception e) {
            Log.w(TAG, "Failed to read sensor orientation", e);
        }
        this.sensorOrientation = so;

        this.cameraThread = new HandlerThread("Camera2Thread-" + cameraId);
        this.cameraThread.start();
        this.cameraHandler = new Handler(cameraThread.getLooper());

        Log.d(TAG, "Created session for camera " + cameraId + ", lensFacing=" + lensFacing
                + ", sensorOrientation=" + this.sensorOrientation
                + ", preview=" + this.previewSize + ", capture=" + this.captureSize);
    }

    public static List<Camera2Info> getAvailableCameras(Context context) {
        List<Camera2Info> result = new ArrayList<>();
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics chars = manager.getCameraCharacteristics(id);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                int lensFacing = (facing != null) ? facing : CameraCharacteristics.LENS_FACING_BACK;

                Size capture = chooseOptimalSize(context, id, lensFacing, true);
                Size preview = chooseOptimalSize(context, id, lensFacing, false);

                result.add(new Camera2Info(id, lensFacing, preview, capture));
                Log.d(TAG, "Camera2Enum: id=" + id + ", facing=" + lensFacing
                        + ", preview=" + preview + ", capture=" + capture);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to enumerate cameras", e);
        }
        return result;
    }

    private static Size chooseOptimalSize(Context ctx, String cameraId, int lensFacing, boolean forCapture) {
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

    public void open(Surface previewSurface, Callback callback) {
        if (isOpen.get()) {
            callback.onError("Camera already open");
            return;
        }

        this.previewSurface = previewSurface;
        this.imageReader = ImageReader.newInstance(
                captureSize.getWidth(),
                captureSize.getHeight(),
                ImageFormat.JPEG,
                2
        );

        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    createCaptureSession(callback);
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                    isOpen.set(false);
                    Log.w(TAG, "Camera " + cameraId + " disconnected");
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    isOpen.set(false);
                    Log.e(TAG, "Camera " + cameraId + " error: " + error);
                    callback.onError("Camera error: " + error);
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "Failed to open camera " + cameraId, e);
            callback.onError("Failed to open camera: " + e.getMessage());
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
        if (captureSession == null || cameraDevice == null) return;

        try {
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(previewSurface);

            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

            captureSession.setRepeatingRequest(previewRequestBuilder.build(),
                    null, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to start preview", e);
        }
    }

    public void capture(String filePath, CaptureCallback callback) {
        if (!isOpen.get() || captureSession == null || cameraDevice == null) {
            callback.onCaptureError("Camera not ready");
            return;
        }
        if (isCapturing.getAndSet(true)) {
            callback.onCaptureError("Capture already in progress");
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

                    byte[] finalBytes = bytes;

                    if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                        Bitmap original = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                        if (original != null) {
                            Matrix m = new Matrix();
                            m.setScale(-1f, 1f);
                            Bitmap mirrored = Bitmap.createBitmap(original, 0, 0,
                                    original.getWidth(), original.getHeight(), m, true);
                            original.recycle();
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            mirrored.compress(Bitmap.CompressFormat.JPEG, 95, baos);
                            mirrored.recycle();
                            finalBytes = baos.toByteArray();
                        }
                    }

                    try (FileOutputStream fos = new FileOutputStream(filePath)) {
                        fos.write(finalBytes);
                    }

                    File f = new File(filePath);
                    long fileSizeKb = f.length() / 1024;
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
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            int displayRotation = context.getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_PORTRAIT ? Surface.ROTATION_0 : Surface.ROTATION_90;
            builder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(displayRotation));

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

    public void close() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            isOpen.set(false);
            isCapturing.set(false);
        } catch (Exception e) {
            Log.e(TAG, "Error closing camera", e);
        }
    }

    public void shutdown() {
        close();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Thread join interrupted", e);
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

    /**
     * Computes the JPEG orientation value for the given display rotation.
     * Sensor output is rotated by sensorOrientation degrees, and needs to be
     * corrected so the image appears upright in the display.
     */
    public int getDisplayRotation(Context context) {
        return context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT
                ? Surface.ROTATION_0 : Surface.ROTATION_90;
    }

    public int getJpegOrientation(int displayRotation) {
        int rotation = 0;
        switch (displayRotation) {
            case Surface.ROTATION_0:   rotation = 0;   break;
            case Surface.ROTATION_90:  rotation = 90;  break;
            case Surface.ROTATION_180: rotation = 180; break;
            case Surface.ROTATION_270: rotation = 270; break;
        }

        if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            // Front camera: mirrored output, no 360- needed.
            return (sensorOrientation + rotation) % 360;
        } else {
            // Back camera: straightforward subtraction.
            return (sensorOrientation - rotation + 360) % 360;
        }
    }

    public interface Callback {
        void onOpened();
        void onError(String error);
    }

    private static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight()
                    - (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}
