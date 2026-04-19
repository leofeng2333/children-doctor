package com.children.doctor.plugins.dualcamera;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.net.Uri;
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

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
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
    private ImageCapture frontImageCapture;
    private ImageCapture backImageCapture;
    private Preview frontPreview;
    private Preview backPreview;

    private CameraSelector frontSelector;
    private CameraSelector backSelector;

    private ViewGroup containerView;
    private FrameLayout frontContainer;
    private FrameLayout backContainer;
    private PreviewView frontPreviewView;
    private PreviewView backPreviewView;

    private boolean isCapturing = false;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    private String pendingFrontPath;
    private String pendingBackPath;
    private PluginCall captureCall;

    private static final String PHOTO_DIR_NAME = "dual_camera_photos";

    public interface PreviewCallback {
        void onError(String error);
        void onCaptureComplete(String frontUri, String backUri, String frontPath, String backPath, long frontFileSizeKb, long backFileSizeKb);
    }

    public DualCameraManager(Context context, FragmentActivity activity, PreviewCallback callback) {
        this.context = context;
        this.activity = activity;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void startPreview(PluginCall call) {
        mainHandler.post(() -> {
            setupCameraProvider(call);
        });
    }

    private void setupCameraProvider(PluginCall call) {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
            ProcessCameraProvider.getInstance(context);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                boolean hasFront = false;
                boolean hasBack = false;
                try {
                    hasFront = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA);
                    hasBack = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA);
                } catch (CameraInfoUnavailableException e) {
                    Log.e(TAG, "Camera info unavailable", e);
                }
                Log.d(TAG, "Has front camera: " + hasFront + ", back camera: " + hasBack);

                if (!hasFront || !hasBack) {
                    call.reject("Device does not have both front and back cameras");
                    return;
                }

                setupPreviewViews(call);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Failed to get camera provider", e);
                call.reject("Failed to get camera provider: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void setupPreviewViews(PluginCall call) {
        frontPreviewView = new PreviewView(context);
        frontPreviewView.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
        frontPreviewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);

        backPreviewView = new PreviewView(context);
        backPreviewView.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
        backPreviewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);

        frontSelector = new CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build();
        backSelector = new CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build();

        frontImageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(new Size(1080, 1440))
                .setJpegQuality(85)
                .build();

        backImageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetResolution(new Size(1080, 1440))
                .setJpegQuality(85)
                .build();

        frontPreview = new Preview.Builder()
                .setTargetResolution(new Size(1080, 1440))
                .setTargetFrameRate(Range.<Integer>create(10, 15))
                .build();
        frontPreview.setSurfaceProvider(frontPreviewView.getSurfaceProvider());

        backPreview = new Preview.Builder()
                .setTargetResolution(new Size(1080, 1440))
                .setTargetFrameRate(Range.<Integer>create(10, 15))
                .build();
        backPreview.setSurfaceProvider(backPreviewView.getSurfaceProvider());

        ViewGroup rootView = (ViewGroup) activity.getWindow().getDecorView().findViewById(android.R.id.content);
        containerView = new LinearLayout(context);
        containerView.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        ((LinearLayout) containerView).setOrientation(LinearLayout.HORIZONTAL);
        ((LinearLayout) containerView).setGravity(Gravity.CENTER);

        int screenWidthPx = context.getResources().getDisplayMetrics().widthPixels;
        int containerWidth = (int) (screenWidthPx * 0.415f);
        int previewHeight = (int) (containerWidth * 4f / 3f);
        int colMargin = dpToPx(16);

        frontContainer = new FrameLayout(context);
        frontContainer.setBackgroundColor(0xFF000000);
        FrameLayout.LayoutParams frontConParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, previewHeight
        );
        frontConParams.setMargins(0, dpToPx(12), 0, dpToPx(12));
        frontContainer.setLayoutParams(frontConParams);
        frontPreviewView.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        frontContainer.addView(frontPreviewView);

        TextView frontLabel = new TextView(context);
        frontLabel.setText("正视图");
        frontLabel.setTextSize(14);
        frontLabel.setTextColor(0xFF000000);
        frontLabel.setGravity(Gravity.CENTER);
        frontLabel.setBackgroundColor(0x00000000);
        frontLabel.setPadding(0, dpToPx(4), 0, dpToPx(8));
        frontLabel.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout frontLayout = new LinearLayout(context);
        frontLayout.setOrientation(LinearLayout.VERTICAL);
        frontLayout.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams frontParams = new LinearLayout.LayoutParams(
            containerWidth, previewHeight + dpToPx(60)
        );
        frontParams.setMargins(colMargin, 0, colMargin / 2, 0);
        frontLayout.setLayoutParams(frontParams);
        frontLayout.addView(frontLabel);
        frontLayout.addView(frontContainer);

        backContainer = new FrameLayout(context);
        backContainer.setBackgroundColor(0xFF000000);
        FrameLayout.LayoutParams backConParams = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, previewHeight
        );
        backConParams.setMargins(0, dpToPx(12), 0, dpToPx(12));
        backContainer.setLayoutParams(backConParams);
        backPreviewView.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        backContainer.addView(backPreviewView);

        TextView backLabel = new TextView(context);
        backLabel.setText("右侧视图");
        backLabel.setTextSize(14);
        backLabel.setTextColor(0xFF000000);
        backLabel.setGravity(Gravity.CENTER);
        backLabel.setBackgroundColor(0x00000000);
        backLabel.setPadding(0, dpToPx(4), 0, dpToPx(8));
        backLabel.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout backLayout = new LinearLayout(context);
        backLayout.setOrientation(LinearLayout.VERTICAL);
        backLayout.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
            containerWidth, previewHeight + dpToPx(60)
        );
        backParams.setMargins(colMargin / 2, 0, colMargin, 0);
        backLayout.setLayoutParams(backParams);
        backLayout.addView(backLabel);
        backLayout.addView(backContainer);

        containerView.addView(frontLayout);
        containerView.addView(backLayout);

        rootView.addView(containerView);

        bindCameras(call);
    }

    private void bindCameras(PluginCall call) {
        if (cameraProvider == null) {
            call.reject("Camera provider not ready");
            return;
        }

        try {
            cameraProvider.unbindAll();

            UseCaseGroup frontUseCaseGroup = new UseCaseGroup.Builder()
                .addUseCase(frontPreview)
                .addUseCase(frontImageCapture)
                .build();

            UseCaseGroup backUseCaseGroup = new UseCaseGroup.Builder()
                .addUseCase(backPreview)
                .addUseCase(backImageCapture)
                .build();

            ConcurrentCamera.SingleCameraConfig frontConfig =
                new ConcurrentCamera.SingleCameraConfig(frontSelector, frontUseCaseGroup, activity);

            ConcurrentCamera.SingleCameraConfig backConfig =
                new ConcurrentCamera.SingleCameraConfig(backSelector, backUseCaseGroup, activity);

            cameraProvider.bindToLifecycle(Arrays.asList(frontConfig, backConfig));
            Log.d(TAG, "Dual cameras bound via ConcurrentCamera API");

            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "ConcurrentCamera bind failed, trying sequential bind", e);

            tryFallbackSequentialBind(call);
        }
    }

    private void tryFallbackSequentialBind(PluginCall call) {
        try {
            cameraProvider.unbindAll();

            cameraProvider.bindToLifecycle(activity, frontSelector, frontPreview, frontImageCapture);
            Log.d(TAG, "Front camera bound (fallback)");

            cameraProvider.bindToLifecycle(activity, backSelector, backPreview, backImageCapture);
            Log.d(TAG, "Back camera bound (fallback)");

            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Fallback bind also failed", e);
            call.reject("Failed to bind cameras: " + e.getMessage());
        }
    }

    public void capture(PluginCall call) {
        if (isCapturing) {
            call.reject("Capture already in progress");
            return;
        }

        if (frontImageCapture == null || backImageCapture == null) {
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
        pendingFrontPath = new File(photoDir, "front_" + timestamp + ".jpg").getAbsolutePath();
        pendingBackPath = new File(photoDir, "back_" + timestamp + ".jpg").getAbsolutePath();

        captureBackCamera();
    }

    private void captureBackCamera() {
        if (backImageCapture == null) {
            isCapturing = false;
            if (captureCall != null) {
                captureCall.reject("Back camera not available");
                captureCall = null;
            }
            return;
        }

        backImageCapture.takePicture(
            executor,
            new ImageCapture.OnImageCapturedCallback() {
                @Override
                public void onCaptureSuccess(@NonNull ImageProxy image) {
                    saveImage(image, pendingBackPath, false, (success) -> {
                        image.close();
                        if (success) {
                            captureFrontCamera();
                        }
                    });
                }

                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    Log.e(TAG, "Back camera capture failed", exception);
                    isCapturing = false;
                    if (captureCall != null) {
                        captureCall.reject("Back camera capture failed: " + exception.getMessage());
                        captureCall = null;
                    }
                }
            }
        );
    }

    private void captureFrontCamera() {
        if (frontImageCapture == null) {
            isCapturing = false;
            if (captureCall != null) {
                captureCall.reject("Front camera not available");
                captureCall = null;
            }
            return;
        }

        frontImageCapture.takePicture(
            executor,
            new ImageCapture.OnImageCapturedCallback() {
                @Override
                public void onCaptureSuccess(@NonNull ImageProxy image) {
                    saveImage(image, pendingFrontPath, true, (frontSuccess) -> {
                        pendingFrontPath = frontSuccess ? pendingFrontPath : null;
                        isCapturing = false;

                        Uri frontUri = frontSuccess ? FileProvider.getUriForFile(context,
                            context.getPackageName() + ".fileprovider", new File(pendingFrontPath)) : null;
                        Uri backUri = pendingBackPath != null ? FileProvider.getUriForFile(context,
                            context.getPackageName() + ".fileprovider", new File(pendingBackPath)) : null;

                        long frontFileSizeKb = frontUri != null ? new File(pendingFrontPath).length() / 1024 : 0;
                        long backFileSizeKb = pendingBackPath != null ? new File(pendingBackPath).length() / 1024 : 0;

                        JSObject result = new JSObject();
                        result.put("frontCameraUrl", frontUri != null ? frontUri.toString() : null);
                        result.put("backCameraUrl", backUri != null ? backUri.toString() : null);
                        result.put("frontCameraPath", pendingFrontPath);
                        result.put("backCameraPath", pendingBackPath);
                        result.put("frontCameraFileSize", frontFileSizeKb);
                        result.put("backCameraFileSize", backFileSizeKb);
                        result.put("frontCameraFileSizeUnit", "KB");
                        result.put("backCameraFileSizeUnit", "KB");
                        result.put("timestamp", System.currentTimeMillis());

                        mainHandler.post(() -> {
                            if (cameraProvider != null) {
                                cameraProvider.unbindAll();
                                cameraProvider = null;
                            }
                            displayCapturedPhotos(frontUri, backUri);

                            if (captureCall != null) {
                                captureCall.resolve(result);
                                captureCall = null;
                            }

                            if (callback != null) {
                                callback.onCaptureComplete(
                                    frontUri != null ? frontUri.toString() : null,
                                    backUri != null ? backUri.toString() : null,
                                    pendingFrontPath,
                                    pendingBackPath,
                                    frontFileSizeKb,
                                    backFileSizeKb
                                );
                            }
                        });
                    });
                }

                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    Log.e(TAG, "Front camera capture failed", exception);
                    isCapturing = false;
                    if (captureCall != null) {
                        captureCall.reject("Front camera capture failed: " + exception.getMessage());
                        captureCall = null;
                    }
                }
            }
        );
    }

    private void displayCapturedPhotos(Uri frontUri, Uri backUri) {
        if (frontContainer != null && frontUri != null) {
            frontContainer.removeAllViews();
            ImageView frontImageView = new ImageView(context);
            frontImageView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
            frontImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            frontImageView.setImageURI(frontUri);
            frontContainer.addView(frontImageView);
        }

        if (backContainer != null && backUri != null) {
            backContainer.removeAllViews();
            ImageView backImageView = new ImageView(context);
            backImageView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
            backImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            backImageView.setImageURI(backUri);
            backContainer.addView(backImageView);
        }

        frontImageCapture = null;
        backImageCapture = null;
        frontPreview = null;
        backPreview = null;

        Log.d(TAG, "Captured photos displayed");
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

            frontContainer = null;
            backContainer = null;
            frontPreviewView = null;
            backPreviewView = null;
            frontImageCapture = null;
            backImageCapture = null;
            frontPreview = null;
            backPreview = null;

            Log.d(TAG, "Preview stopped");
        });
    }

    private int dpToPx(int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }
}
