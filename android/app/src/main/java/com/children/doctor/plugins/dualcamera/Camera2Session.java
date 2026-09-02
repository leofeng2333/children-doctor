package com.children.doctor.plugins.dualcamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.media.ExifInterface;
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
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class Camera2Session {

    private static final String TAG = "Camera2Session";

    /** 静态 CaptureLogger 引用；由 DualCameraPlugin.load() 注入。 */
    private static volatile CaptureLogger captureLogger;

    public static void setCaptureLogger(CaptureLogger logger) {
        captureLogger = logger;
    }

    /**
     * 写一行日志。最重要的契约：<b>任何异常都不能往外抛</b>，调用者都在拍照 / 启动 / 关闭的关键路径上。
     * 这里集中防御：
     * <ul>
     *   <li>captureLogger 未注入 → 直接返回（feature disabled）</li>
     *   <li>captureLogger.java 抛任何异常 → 吞掉，写到 logcat 的 E 通道，便于诊断但不污染业务路径</li>
     * </ul>
     */
    private static void log(String msg) {
        CaptureLogger logger = captureLogger;
        if (logger == null || msg == null) return;
        try {
            logger.java(TAG, msg);
        } catch (Throwable t) {
            // 绝对不能让日志让拍照链路崩溃。
            // 这种情况几乎只发生在 IO 线程死掉之后又 post（RejecetedExecutionException）。
            Log.w(TAG, "captureLogger.java threw: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** 便捷重载：格式化参数。格式化或写日志任何一步抛异常都吞掉。 */
    private static void log(String fmt, Object... args) {
        CaptureLogger logger = captureLogger;
        if (logger == null || fmt == null) return;
        String formatted;
        try {
            formatted = String.format(Locale.US, fmt, args);
        } catch (Throwable t) {
            // args 类型与 fmt 不匹配（IllegalFormatConversionException）时，退回用 "+" 拼接
            StringBuilder sb = new StringBuilder(fmt == null ? "<null fmt>" : fmt);
            if (args != null) {
                sb.append(" | args=[");
                for (int i = 0; i < args.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(args[i] == null ? "null" : String.valueOf(args[i]));
                }
                sb.append(']');
            }
            formatted = sb.toString();
        }
        log(formatted);
    }

    private static final int MAX_CAPTURE_BUFFERS = 2;
    private static final int OPEN_RETRY_COUNT = 3;
    private static final long OPEN_RETRY_DELAY_MS = 800;
    // "被占用"类错误（ERROR_IN_USE / ERROR_MAX_CAMERAS_IN_USE）需要更长时间等 OS 回收
    private static final long OPEN_RETRY_DELAY_MS_BUSY = 1500;

    // 错误码常量兼容层：
    // CameraDevice 的 onError 错误码全部在 CameraDevice.StateCallback 内嵌接口里
    // （不是 CameraDevice 类上），且部分高 API 才引入。本类 minSdk 24，统一用数值常量
    // 兜底 + 注释标记，便于后续维护。
    //   ERROR_IN_USE                  = 4   (API 1,  == ERROR_CAMERA_IN_USE)
    //   ERROR_CAMERA_DISABLED         = 3   (API 23)
    //   ERROR_CAMERA_DEVICE           = 2   (API 23)
    //   ERROR_CAMERA_SERVICE          = 5   (API 23)
    //   ERROR_MAX_CAMERAS_IN_USE      = 6   (API 28)
    private static final int CAMERA_ERROR_IN_USE              = 4;
    private static final int CAMERA_ERROR_CAMERA_DISABLED     = 3;
    private static final int CAMERA_ERROR_CAMERA_DEVICE       = 2;
    private static final int CAMERA_ERROR_CAMERA_SERVICE      = 5;
    private static final int CAMERA_ERROR_MAX_CAMERAS_IN_USE  = 6;

    private final Context context;
    private final HandlerThread cameraThread;
    private final Handler cameraHandler;

    private volatile CameraDevice cameraDevice;
    private volatile CameraCaptureSession captureSession;
    private volatile CaptureRequest.Builder previewRequestBuilder;
    private volatile ImageReader imageReader;

    // ImageReader 实际使用的格式：JPEG（多数摄像头）或 YUV_420_888（UVC 等无 JPEG encoder 的设备）。
    // 在 open()/restartPreviewWithExistingSurface() 时根据摄像头支持的输出格式决定。
    private volatile int captureImageFormat = ImageFormat.JPEG;

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
    private Size captureSize;

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
        log("Created session cameraId=" + cameraId
                + " lensFacing=" + lensFacing
                + " sensorOrientation=" + sensorOrientation
                + " preview=" + this.previewSize
                + " capture=" + this.captureSize);
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
            String[] cameraIds = manager.getCameraIdList();
            Log.d(TAG, "=== Camera enumeration start === device cameras: " + cameraIds.length);

            for (int i = 0; i < cameraIds.length; i++) {
                String id = cameraIds[i];
                try {
                    CameraCharacteristics chars = manager.getCameraCharacteristics(id);

                    Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                    int lensFacing = (facing != null) ? facing : CameraCharacteristics.LENS_FACING_BACK;
                    String facingStr = lensFacingToString(lensFacing);

                    Integer hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                    String hwLevelStr = hwLevelToString(hwLevel);

                    int[] capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                    String capsStr = capabilitiesToString(capabilities);

                    boolean isLogicalMultiCamera = false;
                    if (capabilities != null) {
                        for (int c : capabilities) {
                            if (c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                                isLogicalMultiCamera = true;
                                break;
                            }
                        }
                    }

                    android.hardware.camera2.params.StreamConfigurationMap map =
                            chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    Size[] jpegSizes = (map != null) ? map.getOutputSizes(ImageFormat.JPEG) : null;
                    Size[] previewSizes = (map != null) ? map.getOutputSizes(SurfaceTexture.class) : null;

                    Integer sensorOrientObj = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
                    int sensorOrient = (sensorOrientObj != null) ? sensorOrientObj : -1;

                    Log.d(TAG, String.format(
                            "[CAMERA ENUM] index=%d id=%s facing=%s hwLevel=%s isLogicalMulti=%s jpegSizes=%d previewSizes=%d caps=[%s]",
                            i, id, facingStr, hwLevelStr, isLogicalMultiCamera,
                            jpegSizes != null ? jpegSizes.length : 0,
                            previewSizes != null ? previewSizes.length : 0,
                            capsStr
                    ));
                    log("[CAMERA ENUM] id=" + id
                            + " facing=" + facingStr
                            + " hwLevel=" + hwLevelStr
                            + " sensorOrient=" + sensorOrient
                            + " jpegSizes=" + (jpegSizes != null ? jpegSizes.length : 0)
                            + " previewSizes=" + (previewSizes != null ? previewSizes.length : 0)
                            + " caps=[" + capsStr + "]");

                    Size capture = chooseOptimalSize(context, id, true);
                    Size preview = chooseOptimalSize(context, id, false);

                    result.add(new Camera2Info(id, lensFacing, preview, capture));
                    Log.d(TAG, "Camera2Enum: id=" + id + ", facing=" + lensFacing
                            + ", preview=" + preview + ", capture=" + capture);
                    log("[CAMERA ENUM] chosen for id=" + id
                            + " preview=" + (preview != null ? preview.getWidth() + "x" + preview.getHeight() : "null")
                            + " capture=" + (capture != null ? capture.getWidth() + "x" + capture.getHeight() : "null"));
                } catch (Exception e) {
                    Log.w(TAG, "Skipping camera id=" + id + ": " + e.getMessage());
                }
            }
            Log.d(TAG, "=== Camera enumeration end === enumerable: " + result.size() + " / " + cameraIds.length);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to enumerate cameras", e);
        }
        return result;
    }

    private static String lensFacingToString(int facing) {
        switch (facing) {
            case CameraCharacteristics.LENS_FACING_FRONT:  return "FRONT";
            case CameraCharacteristics.LENS_FACING_BACK:   return "BACK";
            case CameraCharacteristics.LENS_FACING_EXTERNAL: return "EXTERNAL";
            default: return "UNKNOWN(" + facing + ")";
        }
    }

    private static String hwLevelToString(Integer hwLevel) {
        if (hwLevel == null) return "NULL";
        switch (hwLevel) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:        return "LEGACY";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:       return "LIMITED";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:          return "FULL";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:             return "LEVEL3";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL:     return "EXTERNAL";
            default: return "UNKNOWN(" + hwLevel + ")";
        }
    }

    /**
     * 把 CameraDevice.StateCallback 的 error 码转成可读字符串，方便日志排查
     * "一个摄像头开着、另一个没开"的场景。
     * 这些常量在 CameraDevice.StateCallback 内嵌接口里（不是 CameraDevice 类上），
     * 且部分高 API 才引入；用本类顶部声明的数值常量做 case 标签。
     */
    private static String cameraErrorName(int error) {
        switch (error) {
            case CAMERA_ERROR_IN_USE:                 return "IN_USE";             // ERROR_IN_USE / ERROR_CAMERA_IN_USE
            case CAMERA_ERROR_MAX_CAMERAS_IN_USE:     return "MAX_CAMERAS_IN_USE"; // ERROR_MAX_CAMERAS_IN_USE
            case CAMERA_ERROR_CAMERA_DISABLED:        return "CAMERA_DISABLED";
            case CAMERA_ERROR_CAMERA_DEVICE:          return "CAMERA_DEVICE";
            case CAMERA_ERROR_CAMERA_SERVICE:         return "CAMERA_SERVICE";
            default:                                   return "UNKNOWN(" + error + ")";
        }
    }

    private static String capabilitiesToString(int[] capabilities) {
        if (capabilities == null || capabilities.length == 0) return "none";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < capabilities.length; i++) {
            if (i > 0) sb.append(",");
            switch (capabilities[i]) {
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE:
                    sb.append("BACKWARD_COMPATIBLE"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR:
                    sb.append("MANUAL_SENSOR"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING:
                    sb.append("MANUAL_POST"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW:
                    sb.append("RAW"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING:
                    sb.append("PRIVATE_REPROCESSING"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING:
                    sb.append("YUV_REPROCESSING"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT:
                    sb.append("DEPTH_OUTPUT"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME:
                    sb.append("MONOCHROME"); break;
                case CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA:
                    sb.append("LOGICAL_MULTI_CAMERA"); break;
                default:
                    sb.append("0x").append(Integer.toHexString(capabilities[i])); break;
            }
        }
        return sb.toString();
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

    /**
     * 取该摄像头的 StreamConfigurationMap，用于后续判断它支持哪些 output format。
     * 任何异常都返回 null，调用方据此走 JPEG fallback。
     */
    private android.hardware.camera2.params.StreamConfigurationMap getStreamConfigurationMap(String camId) {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics chars = manager.getCameraCharacteristics(camId);
            return chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        } catch (Exception e) {
            Log.w(TAG, "getStreamConfigurationMap failed for " + camId, e);
            return null;
        }
    }

    /**
     * 判断摄像头是否属于"UVC/外接"类（LEGACY 或 EXTERNAL hwLevel），即更可能
     * 没有硬件 JPEG encoder、需要走 YUV 绕过软编码卡死。
     * 内置 FULL/LEVEL3 摄像头继续走 JPEG 路径（HAL 硬件编码、零 CPU 成本）。
     */
    private boolean isLikelyUvcCamera() {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            Integer hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            if (hwLevel == null) return true;  // 拿不到 hwLevel 视为可疑
            return hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
                    || hwLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL;
        } catch (Exception e) {
            Log.w(TAG, "isLikelyUvcCamera failed for " + cameraId, e);
            return true;  // 出错也走 YUV，更保守
        }
    }

    private String hwLevelName() {
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics chars = manager.getCameraCharacteristics(cameraId);
            Integer hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            return hwLevelToString(hwLevel);
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    /**
     * 选择一个最接近 desiredSize 的 YUV_420_888 输出尺寸。返回 null 表示该摄像头不支持 YUV。
     *
     * 选 YUV 的策略：UVC 外接摄像头通常 JPEG encoder 是软件模拟的；直接读 YUV、Java 层
     * 自己压 JPEG，比依赖 HAL 软编码稳定得多。代价是一次 CPU 编码，但单张 1280x960 在
     * ARM A 系列上 < 100ms，且不会卡 HAL 状态机。
     */
    private static Size chooseYuvSizeForCamera(
            android.hardware.camera2.params.StreamConfigurationMap map,
            Size desiredSize) {
        if (map == null) return null;
        Size[] yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        if (yuvSizes == null || yuvSizes.length == 0) {
            Log.d(TAG, "chooseYuvSizeForCamera: no YUV_420_888 support");
            return null;
        }
        // 过滤掉太大的（避免 CPU 编码成为瓶颈）和太小的（避免图像糊）。
        List<Size> candidates = new ArrayList<>();
        for (Size s : yuvSizes) {
            if (s.getWidth() <= 1920 && s.getHeight() <= 1920
                    && s.getWidth() >= 320 && s.getHeight() >= 240) {
                candidates.add(s);
            }
        }
        if (candidates.isEmpty()) {
            // 都太大或都太小，就拿原列表里最接近 desiredSize 的。
            return pickClosestSize(yuvSizes, desiredSize);
        }
        Size best = pickClosestSize(candidates.toArray(new Size[0]), desiredSize);
        Log.d(TAG, "chooseYuvSizeForCamera: desired=" + desiredSize + ", chosen=" + best);
        return best;
    }

    private static Size pickClosestSize(Size[] sizes, Size target) {
        if (sizes == null || sizes.length == 0) return target;
        Size best = sizes[0];
        long bestDiff = Math.abs((long) best.getWidth() * best.getHeight()
                - (long) target.getWidth() * target.getHeight());
        for (Size s : sizes) {
            long diff = Math.abs((long) s.getWidth() * s.getHeight()
                    - (long) target.getWidth() * target.getHeight());
            if (diff < bestDiff) {
                best = s;
                bestDiff = diff;
            }
        }
        return best;
    }

    public void open(Surface previewSurface, SurfaceTexture surfaceTexture, Callback callback) {
        if (isOpen.get()) {
            callback.onError("Camera already open");
            return;
        }

        this.previewSurface = previewSurface;
        this.previewSurfaceTexture = surfaceTexture;
        this.surfaceOwnedBySession = false;
        try {
            // UVC 摄像头（外接 USB）在很多 HAL 上没有硬件 JPEG encoder，要求 Camera2 HAL 在
            // 底层把 YUV 软编码成 JPEG。这种软编码在双摄像头同时拍照 / USB 总线抖动时极易
            // 卡死，导致 onImageAvailable 不回调、整个 capture 走满 15s 超时。
            //
            // 解决思路：直接读 YUV_420_888 buffer，绕过 HAL 软编码。代价是我们在 Java 层
            // 手动做一次 YUV -> JPEG 软编码（YuvImage.compressToJpeg），但这个过程是同步
            // 可控的，不会被 HAL 状态机卡死。
            //
            // 决策逻辑：
            //   1) 如果该摄像头根本不支持 YUV 输出（JPEG-only 摄像头，比如某些老款 MIPI），
            //      走 JPEG ImageReader；
            //   2) 否则用 YUV。
            android.hardware.camera2.params.StreamConfigurationMap map =
                    getStreamConfigurationMap(cameraId);
            boolean preferYuv = isLikelyUvcCamera();
            Size yuvSize = preferYuv ? chooseYuvSizeForCamera(map, captureSize) : null;
            if (yuvSize != null) {
                this.captureImageFormat = ImageFormat.YUV_420_888;
                this.captureSize = yuvSize;
                Log.d(TAG, "open: using YUV_420_888 ImageReader for " + cameraId
                        + " (UVC soft-encode workaround), size=" + yuvSize);
                log("open: ImageReader=YUV_420_888 size=" + yuvSize
                        + " (UVC workaround active, hwLevel=" + hwLevelName()
                        + " preferYuv=" + preferYuv + ")");
                this.imageReader = ImageReader.newInstance(
                        yuvSize.getWidth(),
                        yuvSize.getHeight(),
                        ImageFormat.YUV_420_888,
                        MAX_CAPTURE_BUFFERS
                );
            } else {
                this.captureImageFormat = ImageFormat.JPEG;
                log("open: ImageReader=JPEG size=" + captureSize
                        + " (HAL hardware encoder path, hwLevel=" + hwLevelName()
                        + " preferYuv=" + preferYuv + ")");
                this.imageReader = ImageReader.newInstance(
                        captureSize.getWidth(),
                        captureSize.getHeight(),
                        ImageFormat.JPEG,
                        MAX_CAPTURE_BUFFERS
                );
            }
        } catch (IllegalArgumentException | OutOfMemoryError e) {
            // ImageReader.newInstance 在尺寸非法 / 内存不足时抛 IAE/OOM，
            // 若不在此处拦截，openWithRetryImpl 仍会跑下去、然后 onOpened 里读
            // imageReader.getSurface() 触发 NPE 把 Camera2Thread 整个打挂。
            Log.e(TAG, "open: failed to create ImageReader for size " + captureSize, e);
            this.imageReader = null;
            callback.onError("Failed to create ImageReader: " + e.getMessage());
            return;
        }

        openWithRetryImpl(cameraId, previewSurface, 0, callback);
    }

    private void openWithRetryImpl(String idToOpen, Surface previewSurface, int attempt, Callback callback) {
        Log.d(TAG, String.format(
                "openWithRetryImpl: id=%s attempt=%d/%d targetLensFacing=%s isShutdown=%s",
                idToOpen, attempt + 1, OPEN_RETRY_COUNT + 1,
                (targetLensFacing == CameraCharacteristics.LENS_FACING_FRONT) ? "FRONT" : "BACK",
                isShutdown
        ));
        try {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

            // Pre-check: some ROMs drop ids from getCameraIdList() while another
            // process is holding the camera, and openCamera() will then throw
            // IllegalArgumentException from getCameraCharacteristics(). Detect
            // that here so we can re-enumerate cleanly.
            try {
                String[] knownIds = manager.getCameraIdList();
                boolean idKnown = false;
                for (String known : knownIds) {
                    if (known.equals(idToOpen)) { idKnown = true; break; }
                }
                if (!idKnown) {
                    Log.w(TAG, "openWithRetryImpl: id=" + idToOpen + " is NOT in current getCameraIdList()=" + java.util.Arrays.toString(knownIds));
                } else {
                    Log.d(TAG, "openWithRetryImpl: id=" + idToOpen + " verified in current getCameraIdList()=" + java.util.Arrays.toString(knownIds));
                }
            } catch (CameraAccessException cae) {
                Log.w(TAG, "openWithRetryImpl: pre-check getCameraIdList() failed", cae);
            }

            manager.openCamera(idToOpen, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    Log.d(TAG, "onOpened: id=" + idToOpen + " camera=" + camera + " attempt=" + attempt);
                    log("onOpened cameraId=" + idToOpen + " attempt=" + attempt);
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
                    Log.w(TAG, "onDisconnected: id=" + idToOpen + " attempt=" + attempt);
                    camera.close();
                    cameraDevice = null;
                    isOpen.set(false);
                    callback.onDisconnected();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    final String errorName = cameraErrorName(error);
                    camera.close();
                    cameraDevice = null;
                    isOpen.set(false);
                    Log.e(TAG, "Camera " + idToOpen + " error: " + error
                            + " (" + errorName + "), attempt=" + attempt);
                    log("onError cameraId=" + idToOpen + " errCode=" + error
                            + " (" + errorName + ") attempt=" + attempt);

                    // 全部用本地数值常量，避免依赖高 API 的 StateCallback.ERROR_*
                    // 见类顶部"错误码常量兼容层"注释。
                    // CAMERA_ERROR_CAMERA_DISABLED = 3: 当前设备的摄像头被设备级禁用，重启前永不可用
                    // CAMERA_ERROR_CAMERA_DEVICE   = 2: 设备 fatal，禁用
                    if (error == CAMERA_ERROR_CAMERA_DISABLED
                            || error == CAMERA_ERROR_CAMERA_DEVICE) {
                        Log.e(TAG, "Camera " + idToOpen + " hardware disabled, no retry");
                        callback.onError("Camera unavailable (" + errorName + ")");
                        return;
                    }

                    // "被占用"类错误：
                    //   ERROR_IN_USE             = 4  (== ERROR_CAMERA_IN_USE)
                    //   ERROR_MAX_CAMERAS_IN_USE = 6  (API 28+)
                    boolean isBusyError = error == CAMERA_ERROR_IN_USE
                            || error == CAMERA_ERROR_MAX_CAMERAS_IN_USE;
                    if (isBusyError) {
                        Log.w(TAG, "Camera " + idToOpen + " busy error: " + errorName
                                + ", will wait longer before retry");
                    }

                    if (attempt < OPEN_RETRY_COUNT) {
                        long delay = isBusyError ? OPEN_RETRY_DELAY_MS_BUSY : OPEN_RETRY_DELAY_MS;
                        Log.d(TAG, "Retrying open for camera " + idToOpen + " (attempt "
                                + (attempt + 1) + "/" + (OPEN_RETRY_COUNT + 1)
                                + ", delay=" + delay + "ms)");

                        // 每次重试前重新枚举，避免拿到一份 stale 的 id 列表；
                        // 某些 ROM 在挂起期间会移除正在被其他进程持有的 id。
                        final String nextId = findCameraIdByLensFacing(targetLensFacing);
                        final String idToUse = (nextId != null) ? nextId : idToOpen;
                        if (nextId != null && !nextId.equals(idToOpen)) {
                            Log.d(TAG, "Re-enumerating on retry, found new ID: " + nextId);
                        }
                        final String finalId = idToUse;
                        cameraHandler.postDelayed(() -> openWithRetryImpl(finalId, previewSurface, attempt + 1, callback),
                                delay);
                    } else {
                        callback.onError("Camera error: " + errorName + " (" + error + ")");
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
        } catch (IllegalArgumentException e) {
            // "Unable to retrieve camera characteristics for unknown device" 表明
            // 上一次 enumerate 的 id 已失效（设备被其他 app 占用、插拔、热插拔等）。
            // 不应让整个相机线程崩溃，必须转成业务错误。
            Log.e(TAG, "Camera id " + idToOpen + " is no longer valid (likely released or held by another process)", e);
            String nextId = findCameraIdByLensFacing(targetLensFacing);
            if (nextId != null && !nextId.equals(idToOpen) && attempt < OPEN_RETRY_COUNT) {
                Log.d(TAG, "Re-enumerating after IllegalArgumentException, found new ID: " + nextId);
                final String finalId = nextId;
                cameraHandler.postDelayed(() -> openWithRetryImpl(finalId, previewSurface, attempt + 1, callback),
                        OPEN_RETRY_DELAY_MS);
            } else {
                callback.onError("Camera device unavailable: " + e.getMessage());
            }
        } catch (RuntimeException e) {
            // 兜底：camera 框架偶发抛 RuntimeException（状态机、IPC 失败等），
            // 不能让 Camera2Thread-0 整个挂掉。
            Log.e(TAG, "Unexpected runtime error opening camera " + idToOpen, e);
            callback.onError("Unexpected camera error: " + e.getMessage());
        }
    }

    private void createCaptureSession(Callback callback) {
        try {
            // imageReader 可能在 onOpened 排队期间被 closeCaptureSessionInternal()
            // 释放（onOpened 在 cameraHandler 跑，close 也 post 到同一 looper，
            // 谁先执行取决于 post 顺序）。一旦为 null 必须走业务错误回退，
            // 不能在 onOpened 线程上抛 NPE，否则整个 Camera2Thread-1 会被打挂。
            if (cameraDevice == null) {
                Log.w(TAG, "createCaptureSession: cameraDevice is null, aborting");
                callback.onError("Camera closed before session created");
                return;
            }
            if (imageReader == null) {
                Log.w(TAG, "createCaptureSession: imageReader is null, aborting");
                callback.onError("ImageReader released before session created");
                return;
            }
            final Surface readerSurface = imageReader.getSurface();
            if (readerSurface == null) {
                Log.w(TAG, "createCaptureSession: imageReader.getSurface() returned null, aborting");
                callback.onError("ImageReader surface unavailable");
                return;
            }
            if (previewSurface == null) {
                Log.w(TAG, "createCaptureSession: previewSurface is null, aborting");
                callback.onError("Preview surface unavailable");
                return;
            }

            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(previewSurface);
            surfaces.add(readerSurface);

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "Session onConfigured: cameraDevice=" + cameraDevice);
                    log("captureSession.onConfigured cameraId=" + cameraId
                            + " surfaces=" + surfaces.size()
                            + " (1=previewSurface, 2=+imageReader.ImageFormat="
                            + (captureImageFormat == ImageFormat.JPEG ? "JPEG" : "YUV_420_888") + ")");
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
                    isOpen.set(true);
                    startPreview();
                    callback.onOpened();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    log("captureSession.onConfigureFailed cameraId=" + cameraId
                            + " imageReaderFormat="
                            + (captureImageFormat == ImageFormat.JPEG ? "JPEG" : "YUV_420_888")
                            + " captureSize=" + captureSize
                            + " previewSize=" + previewSize);
                    captureSession = null;
                    callback.onError("Session configuration failed");
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to create capture session", e);
            log("captureSession.createCameraAccessException cameraId=" + cameraId + " err=" + e.getMessage());
            callback.onError("Failed to create session: " + e.getMessage());
        } catch (NullPointerException | IllegalStateException e) {
            // 兜底：cameraDevice / captureSession 在 callback 排队期间被另一线程 close，
            // 或者 ImageReader 处于已关闭状态。会拿到 NPE/ISE，必须转成业务错误。
            Log.e(TAG, "Capture session aborted due to concurrent close", e);
            log("captureSession.concurrentClose cameraId=" + cameraId + " err=" + e.getMessage());
            callback.onError("Camera closed during session create: " + e.getMessage());
        } catch (RuntimeException e) {
            // 兜底：任何漏网的运行时异常（IPC 失败、状态机错乱等），
            // 都不能让 Camera2Thread 整个挂掉。
            Log.e(TAG, "Unexpected runtime error creating capture session", e);
            log("captureSession.unexpectedRuntimeError cameraId=" + cameraId + " err=" + e.getMessage());
            callback.onError("Unexpected session error: " + e.getMessage());
        }
    }

    private void startPreview() {
        if (isShutdown) {
            Log.w(TAG, "startPreview: session is shutdown, skipping");
            return;
        }
        // Snapshot the references so a concurrent close() on another thread
        // can't null them out between the guard and the use (would NPE).
        final CameraCaptureSession session = captureSession;
        final CameraDevice device = cameraDevice;
        if (session == null || device == null) {
            Log.w(TAG, "startPreview: session=" + session + ", device=" + device);
            return;
        }
        if (!isPreviewActive.compareAndSet(false, true)) {
            Log.d(TAG, "startPreview: preview already active");
            return;
        }

        try {
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);

            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

            session.setRepeatingRequest(builder.build(), null, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "Failed to start preview", e);
            isPreviewActive.set(false);
        } catch (NullPointerException e) {
            // Defensive: captureSession/cameraDevice were nulled by a
            // concurrent close() between the snapshot and native call.
            Log.w(TAG, "startPreview: NPE after snapshot, session was closed concurrently", e);
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
        try {
            // 与 open() 同理：优先 YUV，避免 UVC HAL 软编码卡死。
            android.hardware.camera2.params.StreamConfigurationMap map =
                    getStreamConfigurationMap(cameraId);
            boolean preferYuv = isLikelyUvcCamera();
            Size yuvSize = preferYuv ? chooseYuvSizeForCamera(map, captureSize) : null;
            if (yuvSize != null) {
                this.captureImageFormat = ImageFormat.YUV_420_888;
                this.captureSize = yuvSize;
                Log.d(TAG, "restartPreviewWithExistingSurface: using YUV_420_888 ImageReader for "
                        + cameraId + ", size=" + yuvSize);
                log("restartPreviewWithExistingSurface: ImageReader=YUV_420_888 size=" + yuvSize
                        + " (UVC workaround active, hwLevel=" + hwLevelName()
                        + " preferYuv=" + preferYuv + ")");
                this.imageReader = ImageReader.newInstance(
                        yuvSize.getWidth(),
                        yuvSize.getHeight(),
                        ImageFormat.YUV_420_888,
                        MAX_CAPTURE_BUFFERS
                );
            } else {
                this.captureImageFormat = ImageFormat.JPEG;
                this.captureSize = captureSize;
                log("restartPreviewWithExistingSurface: ImageReader=JPEG size=" + captureSize
                        + " (HAL hardware encoder path, hwLevel=" + hwLevelName()
                        + " preferYuv=" + preferYuv + ")");
                this.imageReader = ImageReader.newInstance(
                        captureSize.getWidth(),
                        captureSize.getHeight(),
                        ImageFormat.JPEG,
                        MAX_CAPTURE_BUFFERS
                );
            }
        } catch (IllegalArgumentException | OutOfMemoryError e) {
            // 见 open() 注释：不让 onOpened 跑到 null imageReader 上 NPE。
            Log.e(TAG, "restartPreviewWithExistingSurface: failed to create ImageReader for size " + captureSize, e);
            this.imageReader = null;
            callback.onError("Failed to create ImageReader: " + e.getMessage());
            return;
        }

        openWithRetryImpl(cameraId, previewSurface, 0, callback);
    }

    private void closeCaptureSession() {
        // Must run on cameraHandler so the close + null-out is serialized
        // with onConfigured / startPreview messages on the same looper.
        // If the thread is already shut down, fall back to direct call.
        if (cameraHandler != null && cameraThread != null && cameraThread.isAlive()) {
            final CountDownLatch done = new CountDownLatch(1);
            cameraHandler.post(() -> {
                closeCaptureSessionInternal();
                done.countDown();
            });
            try {
                if (!done.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    Log.w(TAG, "closeCaptureSession: timed out waiting for cameraHandler, forcing direct close");
                    closeCaptureSessionInternal();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                closeCaptureSessionInternal();
            }
        } else {
            closeCaptureSessionInternal();
        }
    }

    private void closeCaptureSessionInternal() {
        try {
            CameraCaptureSession s = captureSession;
            captureSession = null;
            if (s != null) s.close();
        } catch (Exception e) {
            Log.e(TAG, "Error closing capture session", e);
        }
        try {
            CameraDevice d = cameraDevice;
            cameraDevice = null;
            if (d != null) d.close();
        } catch (Exception e) {
            Log.e(TAG, "Error closing camera device", e);
        }
        ImageReader r = imageReader;
        imageReader = null;
        if (r != null) r.close();
    }

    // 软超时：如果 captureSession.capture() 提交后超过这个时间还没拿到 image，
    // 主动取消并报"软超时"，避免走到 Camera2Controller 的硬超时（15s）。
    // 这个时间留得比 15s 短，目的是在 HAL 软编码卡死时能"快速失败 + 内部自动重试"，
    // 给用户的感觉是"稍微等一下就成功"，而不是"等满 15s 然后失败"。
    private static final long SOFT_CAPTURE_TIMEOUT_MS = 8_000;

    public void capture(String filePath, CountDownLatch latch, CaptureCallback callback) {
        if (!isOpen.get() || captureSession == null || cameraDevice == null) {
            Log.w(TAG, "capture() rejected: isOpen=" + isOpen.get()
                    + " captureSession=" + captureSession
                    + " cameraDevice=" + cameraDevice);
            callback.onCaptureError("Camera not ready");
            latch.countDown();
            return;
        }
        if (!isCapturing.compareAndSet(false, true)) {
            Log.w(TAG, "capture() rejected: already capturing for cameraId=" + cameraId);
            callback.onCaptureError("Capture already in progress");
            latch.countDown();
            return;
        }

        this.captureCallback = callback;
        final long captureStartMs = System.currentTimeMillis();
        log("capture start cameraId=" + cameraId
                + " format=" + captureImageFormat
                + " captureSize=" + captureSize
                + " filePath=" + filePath);

imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireNextImage();
                        if (image == null) {
                            isCapturing.set(false);
                            callback.onCaptureError("No image available");
                            log("onImageAvailable cameraId=" + cameraId + " FAILED: image null");
                            return;
                        }

                        long encodeStart = System.currentTimeMillis();
                        int rawBytes = 0;
                        byte[] bytes;
                        if (captureImageFormat == ImageFormat.YUV_420_888) {
                            bytes = yuvImageToJpegBytes(image);
                            // YUV 路径无法在编码前直接拿"原始字节数"，用 JPEG 输出大小做对比基准
                            rawBytes = bytes != null ? bytes.length : 0;
                        } else {
                            // JPEG 路径：直接拿 plane 0 的 buffer。
                            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                            bytes = new byte[buffer.remaining()];
                            buffer.get(bytes);
                            rawBytes = bytes.length;
                        }
                        long encodeMs = System.currentTimeMillis() - encodeStart;

                        byte[] finalBytes = applyFrontMirrorIfNeeded(bytes);
                        int finalBytesLen = finalBytes != null ? finalBytes.length : 0;
                        boolean changedByMirror = (finalBytesLen != rawBytes);

                        try (FileOutputStream fos = new FileOutputStream(filePath)) {
                            fos.write(finalBytes);
                        }

                        // 诊断：写盘后用 ExifInterface 读回 orientation+dimensions，
                        // 用来核对"HAL 写入的 EXIF rotation"vs"我们后来 bitmap 旋转时是否覆盖了 EXIF"。
                        // 注意：BitmapFactory.compress 不会自动写回 EXIF，所以 JPEG 路径下 EXIF 保留 HAL 的值。
                        String exifInfo = "n/a";
                        try {
                            ExifInterface exif = new ExifInterface(filePath);
                            int rotationTag = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                            int exifW = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0);
                            int exifH = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0);
                            exifInfo = "rotation=" + rotationTag + " dims=" + exifW + "x" + exifH;
                        } catch (Throwable exifErr) {
                            exifInfo = "read-failed(" + exifErr.getClass().getSimpleName() + ")";
                        }

                        long fileSizeKb = new File(filePath).length() / 1024;
                        long totalMs = System.currentTimeMillis() - captureStartMs;
                        log("onImageAvailable cameraId=" + cameraId
                                + " format=" + (captureImageFormat == ImageFormat.JPEG ? "JPEG" : "YUV_420_888")
                                + " captureSize=" + captureSize.getWidth() + "x" + captureSize.getHeight()
                                + " rawBytes=" + rawBytes
                                + " finalBytes=" + finalBytesLen
                                + " changedByMirror=" + changedByMirror
                                + " encodeMs=" + encodeMs
                                + " totalMs=" + totalMs
                                + " fileSizeKb=" + fileSizeKb
                                + " exif=" + exifInfo
                                + " filePath=" + filePath);
                        isCapturing.set(false);
                        callback.onCaptureSuccess(filePath, fileSizeKb);
                    } catch (Exception e) {
                        Log.e(TAG, "Capture processing failed", e);
                        log("Capture processing failed cameraId=" + cameraId + " err=" + e.getMessage());
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
            int jpegOrientation = getJpegOrientation(getDisplayRotation());
            builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);
            log("capture REQ cameraId=" + cameraId
                    + " format=" + (captureImageFormat == ImageFormat.JPEG ? "JPEG" : "YUV_420_888")
                    + " captureSize=" + captureSize.getWidth() + "x" + captureSize.getHeight()
                    + " sensorOrient=" + sensorOrientation
                    + " displayRot=" + getDisplayRotation()
                    + " jpegOrientation=" + jpegOrientation
                    + " globalForceBackMirror=" + globalForceBackMirror);

            captureSession.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session,
                                              @NonNull CaptureRequest request,
                                              long timestamp, long frameNumber) {
                    Log.d(TAG, "Capture started for " + cameraId + " frame=" + frameNumber);
                    log("capture STARTED cameraId=" + cameraId
                            + " frame=" + frameNumber
                            + " ts=" + timestamp);
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    Log.d(TAG, "Capture completed for " + cameraId);
                    Object orientObj = null;
                    try {
                        orientObj = result.get(CaptureResult.JPEG_ORIENTATION);
                    } catch (Throwable ignored) {
                        // JPEG_ORIENTATION 只对 JPEG output 有效；YUV 路径下 result 里可能没有这个 key。
                    }
                    log("capture COMPLETED cameraId=" + cameraId
                            + " jpegOrientation=" + orientObj
                            + " sensorOrientation=" + sensorOrientation);
                }
            }, cameraHandler);

            // 软超时：到点后如果 HAL 还没给我们 image，就主动调用 stopRepeating + abortCaptures
            // 强制状态机走完。注意：这里必须在 cameraHandler 上 post，因为 abortCaptures 不是线程安全的。
            cameraHandler.postDelayed(() -> {
                if (!isCapturing.get()) return;  // 已经成功
                Log.w(TAG, "capture soft-timeout after " + SOFT_CAPTURE_TIMEOUT_MS
                        + "ms for cameraId=" + cameraId
                        + " format=" + captureImageFormat
                        + " (UVC HAL soft-encode hang, aborting captures)");
                log("capture SOFT-TIMEOUT cameraId=" + cameraId
                        + " after " + SOFT_CAPTURE_TIMEOUT_MS + "ms"
                        + " format=" + captureImageFormat
                        + " (HAL state machine hang suspected, aborting captures)");
                CameraCaptureSession s = captureSession;
                if (s != null) {
                    try { s.stopRepeating(); } catch (Exception ignored) {}
                    try { s.abortCaptures(); } catch (Exception ignored) {}
                }
                // ImageReader 的 listener 不会被调用了，主动回调错误让 latch 归零。
                isCapturing.set(false);
                callback.onCaptureError("Capture soft-timeout (no frame in "
                        + SOFT_CAPTURE_TIMEOUT_MS + "ms)");
            }, SOFT_CAPTURE_TIMEOUT_MS);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Capture failed", e);
            isCapturing.set(false);
            callback.onCaptureError("Capture failed: " + e.getMessage());
            latch.countDown();
        } catch (IllegalStateException e) {
            Log.e(TAG, "Capture illegal state (session/device closed)", e);
            isCapturing.set(false);
            callback.onCaptureError("Capture illegal state: " + e.getMessage());
            latch.countDown();
        }
    }

    /**
     * 把 YUV_420_888 Image 转成 JPEG bytes。
     *
     * YUV_420_888 在 Android 上有 3 个 plane（Y/U/V），但 stride 可能大于 width。
     * YuvImage 只接受 NV21（Y 全平面 + VU 交错平面）的紧密排列，所以要先做一次拷贝。
     */
    private byte[] yuvImageToJpegBytes(Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        Image.Plane[] planes = image.getPlanes();

        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int yPixStride = planes[0].getPixelStride();
        int uRowStride = planes[1].getRowStride();
        int vRowStride = planes[2].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vPixelStride = planes[2].getPixelStride();

        // NV21 紧密布局：Y 占 w*h，VU 交错占 w*h/2。
        byte[] nv21 = new byte[w * h * 3 / 2];
        int yDestPos = 0;
        int uvDestPos = w * h;

        // Y plane：逐像素拷贝（处理 pixelStride > 1 的情况）
        byte[] yTmp = new byte[yRowStride];
        for (int row = 0; row < h; row++) {
            yBuf.position(row * yRowStride);
            int toRead = Math.min(yRowStride, yBuf.remaining());
            yBuf.get(yTmp, 0, toRead);
            if (yPixStride == 1) {
                // 紧密布局：整行直接拷贝
                System.arraycopy(yTmp, 0, nv21, yDestPos, w);
                yDestPos += w;
            } else {
                // 步进布局：每像素读一个字节
                for (int col = 0; col < w; col++) {
                    nv21[yDestPos++] = yTmp[col * yPixStride];
                }
            }
        }

        // U + V interleaved as VU (NV21)
        byte[] uTmp = new byte[uRowStride];
        byte[] vTmp = new byte[vRowStride];
        for (int row = 0; row < h / 2; row++) {
            uBuf.position(row * uRowStride);
            vBuf.position(row * vRowStride);
            int uToRead = Math.min(uRowStride, uBuf.remaining());
            uBuf.get(uTmp, 0, uToRead);
            int vToRead = Math.min(vRowStride, vBuf.remaining());
            vBuf.get(vTmp, 0, vToRead);
            for (int col = 0; col < w / 2; col++) {
                nv21[uvDestPos++] = vTmp[col * vPixelStride];
                nv21[uvDestPos++] = uTmp[col * uPixelStride];
            }
        }

        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, w, h), 92, baos);
        byte[] jpeg = baos.toByteArray();

        // 诊断：YUV 输入尺寸 + plane 步进布局 + JPEG 输出大小。
        // 关键：UVC HAL 给我们的是 sensor 原始朝向，1280x720 通常是横屏 layout；
        // 如果看到 w<h 说明 HAL 已经在底层转过——是非常有价值的反推信息。
        log("yuvImageToJpegBytes cameraId=" + cameraId
                + " input=" + w + "x" + h
                + " yRowStride=" + yRowStride
                + " yPixStride=" + yPixStride
                + " uRowStride=" + uRowStride
                + " vRowStride=" + vRowStride
                + " uPixelStride=" + uPixelStride
                + " vPixelStride=" + vPixelStride
                + " jpegBytes=" + (jpeg != null ? jpeg.length : 0));
        return jpeg;
    }

    // 全局兜底：所有后置（UVC / 内置后置）拍照时是否强制水平镜像。
    // 一些 UVC 摄像头的硬件层在预览路径已做水平镜像，但拍照走 raw 输出未镜像，
    // 表现为"预览正常，拍照左右翻转"。打开此开关后所有 slot 的后置摄像头拍照都会镜像。
    private static volatile boolean globalForceBackMirror = false;
    // 单 session 覆盖：高于全局开关。用于对某些特殊机型精细控制。
    private boolean forceMirrorBack = false;

    /**
     * 每个摄像头的方向校准量（实验调参用）。
     * extraRotate：额外旋转角度（0/90/180/270），叠加在公式算出的旋转角上。
     * extraMirror：额外水平镜像，true = 再补一次 postScale(-1,1)。
     * 这两个值在校准后固定写入，运行时通过 {@link #setCalibration} 设置。
     */
    private volatile int extraRotate = 0;
    private volatile boolean extraMirror = false;

    /**
     * 拍照方向的独立旋转偏移（不参与预览 transform，仅作用于拍照 YUV / JPEG 输出）。
     *
     * <p>背景：目标机器 UVC 摄像头预览方向和拍照方向不一致。
     * 原来的 {@link #extraRotate} 同时影响预览 transform 和拍照 YUV rotation，会导致"预览正了但照片偏"。
     * 拆出这个字段后，预览用 {@code extraRotate}，拍照用 {@code captureRotationOffset}，两条链路独立调参。
     */
    private volatile int captureRotationOffset = 0;

    /** 全局开关：true 时所有后置摄像头的拍照输出都会水平镜像。 */
    public static void setGlobalForceBackMirror(boolean v) { globalForceBackMirror = v; }
    /** 单 session 覆盖。 */
    public void setForceMirrorBack(boolean v) { this.forceMirrorBack = v; }
    /**
     * 设置摄像头方向校准量。
     * @param extraRotateDegrees 额外旋转角（0/90/180/270）
     * @param extraMirrorNeeded true=再补一次水平镜像
     */
    public void setCalibration(int extraRotateDegrees, boolean extraMirrorNeeded) {
        this.extraRotate = ((extraRotateDegrees % 360) + 360) % 360;
        this.extraMirror = extraMirrorNeeded;
        Log.d(TAG, "setCalibration cameraId=" + cameraId
                + " extraRotate=" + this.extraRotate + " extraMirror=" + this.extraMirror);
        log("setCalibration cameraId=" + cameraId
                + " extraRotate=" + this.extraRotate + " extraMirror=" + this.extraMirror);
    }
    public int getExtraRotate() { return extraRotate; }
    public boolean getExtraMirror() { return extraMirror; }

    /**
     * 设置拍照方向的额外旋转（与预览的 {@link #setCalibration} 完全独立）。
     * @param degrees 0/90/180/270；非 90 倍数会被规范化
     */
    public void setCaptureRotationOffset(int degrees) {
        this.captureRotationOffset = ((degrees % 360) + 360) % 360;
        Log.d(TAG, "setCaptureRotationOffset cameraId=" + cameraId
                + " captureRotationOffset=" + this.captureRotationOffset);
        log("setCaptureRotationOffset cameraId=" + cameraId
                + " captureRotationOffset=" + this.captureRotationOffset);
    }
    public int getCaptureRotationOffset() { return captureRotationOffset; }

    /**
     * 对拍照得到的 JPEG bytes 做方向/镜像修正，让最终文件 = 预览方向。
     *
     * <p>两条路径：
     * <ul>
     *   <li>JPEG（HAL 编码）：HAL 已按 {@code CaptureRequest.JPEG_ORIENTATION} 写入 EXIF rotation，
     *       BitmapFactory.decodeByteArray 会自动按 EXIF 旋转，所以这里只做水平镜像修正（前置默认镜像 / 后置 globalForceBackMirror）。</li>
     *   <li>YUV（Java 层软编码）：HAL 不会写 EXIF rotation，必须手动旋转 + 镜像。
     *       旋转角度 = 与预览 TextureView 的 setTransform 一致（{@code (sensorOrientation - displayRotDeg + 360) % 360}）。</li>
     * </ul>
     *
     * <p>日志：所有路径都写入 CaptureLogger，便于诊断"照片 vs 预览"差异。
     */
    private byte[] applyFrontMirrorIfNeeded(byte[] bytes) {
        boolean wasAlreadyRotatedByExif = (captureImageFormat == ImageFormat.JPEG);
        // UVC 外接摄像头被 HAL 错误报告为 LENS_FACING_FRONT 时，按 BACK（外接）处理：
        //   1) 不自动水平镜像；
        //   2) 旋转公式与后置一致。
        // 这样可让拍照输出方向与预览 TextureView 的 setTransform 公式严格一致。
        boolean isExternal = (lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL);
        boolean isFront = (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) && !isExternal;
        // EXTERNAL（外接 UVC）一律不做水平镜像：
        //   - 系统相机对外接摄像头也不会自动镜像；
        //   - UVC HAL 给的是 sensor 原始数据，预览和拍照通常方向一致，不需要再"补一次镜像"。
        // BACK 类摄像头仍可受 globalForceBackMirror 控制，用于解决某些老 HAL 拍照/预览不一致。
        boolean needMirror = isFront || (forceMirrorBack && !isExternal);

        int displayRotDeg;
        switch (getDisplayRotation()) {
            case Surface.ROTATION_90:  displayRotDeg = 90;  break;
            case Surface.ROTATION_180: displayRotDeg = 180; break;
            case Surface.ROTATION_270: displayRotDeg = 270; break;
            default:                    displayRotDeg = 0;   break;
        }

        // YUV 路径下，照片方向必须 = 预览方向（与 configureTransform 完全一致）
        // 后置 / 外接（UVC 被 HAL 错报为 FRONT 时也走这里）：(sensor - displayRot + 360) % 360
        // 前置：(sensor + displayRot + 360) % 360
        // 注意：预览在 configureTransform 里对所有 FRONT 都 postScale(-1,1) + postRotate(rotation)。
        //       当镜头实际是 EXTERNAL（外接 UVC）时，为保持预览与拍照一致，这里跳过镜像、
        //       并把"先镜像再旋转"等价为"先旋转 90 再加额外旋转/翻转到与预览一致"——
        //       但更稳的做法是让预览侧也对 EXTERNAL 不做 postScale，下面 Camera2Controller
        //       已同步修改。这里用 BACK 公式即可保证两者公式一致。
        int yuvDegrees;
        if (isFront) {
            yuvDegrees = (sensorOrientation + displayRotDeg + 360) % 360;
        } else {
            yuvDegrees = (sensorOrientation - displayRotDeg + 360) % 360;
        }
        // 叠加校准偏移量：
        //   - extraRotate 仍叠加（兼容旧校准链）
        //   - captureRotationOffset 是独立的拍照方向偏移，与预览解耦
        int finalDegrees = (yuvDegrees + extraRotate + captureRotationOffset) % 360;
        boolean finalNeedMirror = extraMirror ? !needMirror : needMirror;

        String formatStr;
        switch (captureImageFormat) {
            case ImageFormat.JPEG: formatStr = "JPEG"; break;
            case ImageFormat.YUV_420_888: formatStr = "YUV_420_888"; break;
            default: formatStr = "ImageFormat(" + captureImageFormat + ")";
        }
        String facingStr;
        switch (lensFacing) {
            case CameraCharacteristics.LENS_FACING_FRONT:    facingStr = "FRONT"; break;
            case CameraCharacteristics.LENS_FACING_BACK:     facingStr = "BACK"; break;
            case CameraCharacteristics.LENS_FACING_EXTERNAL: facingStr = "EXTERNAL"; break;
            default:                                          facingStr = "UNKNOWN(" + lensFacing + ")";
        }

        log("applyMirror cameraId=" + cameraId
                + " format=" + formatStr
                + " facing=" + facingStr
                + " sensorOrient=" + sensorOrientation
                + " displayRot=" + displayRotDeg
                + " wasAlreadyRotatedByExif=" + wasAlreadyRotatedByExif
                + " isFront=" + isFront
                + " forceMirrorBack=" + forceMirrorBack
                + " globalForceBackMirror=" + globalForceBackMirror
                + " needMirror=" + needMirror
                + " extraRotate=" + extraRotate + " extraMirror=" + extraMirror
                + " captureRotationOffset=" + captureRotationOffset
                + " finalDegrees=" + finalDegrees + " finalNeedMirror=" + finalNeedMirror
                + " jpegOrientation=" + getJpegOrientation(getDisplayRotation()));

        if (!finalNeedMirror) {
            if (wasAlreadyRotatedByExif) {
                log("applyMirror branch=JPEG-noMirror (EXIF rotation already applied by HAL, no extra transform)");
                return bytes;
            }
            log("applyMirror branch=YUV-rotateOnly degrees=" + finalDegrees);
            return rotateJpeg(bytes, finalDegrees);
        }

        if (wasAlreadyRotatedByExif) {
            // JPEG 路径：HAL 已按 JPEG_ORIENTATION 写入 EXIF rotation。
            // EXIF 不区分前置/后置镜像——HAL 默认前置水平镜像预览，拍照时通常也镜像（取决于 HAL）。
            // 我们不能假设 HAL 在拍照时也镜像了前置（不同设备行为不同），所以强制水平镜像一次保证一致。
            // 后置走 globalForceBackMirror=true 时也需要镜像。
            log("applyMirror branch=JPEG-mirrorOnly (EXIF rotation already applied by HAL, applying horizontal mirror)");
            return mirrorJpegOnly(bytes);
        }

        // YUV 路径：照片方向 = 预览方向 + 水平镜像
        log("applyMirror branch=YUV-rotateAndMirror degrees=" + finalDegrees);
        return rotateAndMirrorJpeg(bytes, finalDegrees);
    }

    /** 仅水平镜像（用于 JPEG 路径：EXIF 已旋转，只需水平镜像修正）。 */
    private byte[] mirrorJpegOnly(byte[] bytes) {
        Bitmap original = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (original == null) {
            log("mirrorJpegOnly: BitmapFactory.decodeByteArray returned null, returning original bytes");
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

    /** 仅旋转（后置摄像头 UVC / 不需要镜像时）。 */
    private byte[] rotateJpeg(byte[] bytes, int degrees) {
        if (degrees == 0) return bytes;
        Bitmap original = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (original == null) return bytes;
        int w = original.getWidth();
        int h = original.getHeight();
        Matrix m = new Matrix();
        m.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(original, 0, 0, w, h, m, true);
        if (rotated != original) original.recycle();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        rotated.compress(Bitmap.CompressFormat.JPEG, 95, baos);
        rotated.recycle();
        return baos.toByteArray();
    }

    /**
     * 旋转 + 水平镜像（前置 UVC / 强制镜像的后置 UVC）。
     *
     * <p>统一为：先按 degrees 旋转，再绕中心水平镜像。比之前 if/else 90 vs others 更稳。
     * UVC 后置常见 sensor=0, displayRot=0 → degrees=0 → 仅水平镜像。
     */
    private byte[] rotateAndMirrorJpeg(byte[] bytes, int degrees) {
        Bitmap original = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (original == null) {
            log("rotateAndMirrorJpeg: BitmapFactory.decodeByteArray returned null, returning original bytes");
            return bytes;
        }
        int w = original.getWidth();
        int h = original.getHeight();
        Matrix m = new Matrix();
        m.postRotate(degrees);
        // 绕中心水平镜像：避免越界、避免依赖旋转后的尺寸
        m.postScale(-1f, 1f, w / 2f, h / 2f);
        Bitmap transformed = Bitmap.createBitmap(original, 0, 0, w, h, m, true);
        original.recycle();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        transformed.compress(Bitmap.CompressFormat.JPEG, 95, baos);
        transformed.recycle();
        return baos.toByteArray();
    }

    public void close() {
        // Mark shutdown first so any in-flight onConfigured / startPreview
        // messages see it and bail out before we tear down references.
        isShutdown = true;

        // If the camera thread is still alive, perform the actual teardown
        // on it so it serializes with onConfigured / startPreview messages.
        // The teardown itself is idempotent, so posting is always safe.
        if (cameraHandler != null && cameraThread != null && cameraThread.isAlive()) {
            final CountDownLatch done = new CountDownLatch(1);
            boolean posted = cameraHandler.post(() -> {
                try {
                    closeInternal();
                } finally {
                    done.countDown();
                }
            });
            if (posted) {
                try {
                    if (!done.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        Log.w(TAG, "close: timed out waiting for cameraHandler, forcing direct close");
                        closeInternal();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    closeInternal();
                }
                return;
            }
        }
        closeInternal();
    }

    private void closeInternal() {
        try {
            CameraCaptureSession s = captureSession;
            captureSession = null;
            if (s != null) s.close();
        } catch (Exception e) {
            Log.e(TAG, "Error closing capture session", e);
        }

        try {
            CameraDevice d = cameraDevice;
            cameraDevice = null;
            if (d != null) d.close();
        } catch (Exception e) {
            Log.e(TAG, "Error closing camera device", e);
        }

        ImageReader r = imageReader;
        imageReader = null;
        if (r != null) r.close();

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

        // UVC 外接摄像头被 HAL 错报为 LENS_FACING_FRONT 时，与 EXTERNAL/BACK 同等处理：
// 用 (sensor - displayRot + 360) % 360。这样 CaptureRequest.JPEG_ORIENTATION 与
// 预览 setTransform 旋转角度公式保持一致（JPEG 路径下 EXIF rotation 与预览方向对齐）。
if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT
        && lensFacing != CameraCharacteristics.LENS_FACING_EXTERNAL) {
    return (sensorOrientation + rotation) % 360;
}
return (sensorOrientation - rotation + 360) % 360;
    }

    private static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight()
                    - (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}
