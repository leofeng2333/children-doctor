package com.children.doctor.plugins.dualcamera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@CapacitorPlugin(name = "DualCamera", permissions = {
    @Permission(alias = "camera", strings = Manifest.permission.CAMERA)
})
public class DualCameraPlugin extends Plugin {

    private static final String TAG = "DualCameraPlugin";

    private DualCameraManager cameraManager;
    private PhotoUploader photoUploader;
    private ImageSplitter imageSplitter;
    private CaptureLogger captureLogger;

    @Override
    public void load() {
        super.load();
        photoUploader = new PhotoUploader();
        imageSplitter = new ImageSplitter(getContext());
        captureLogger = CaptureLogger.get(getContext());
        // 把 logger 注入到静态类，让 Camera2Session / Camera2Controller 也能用它。
        Camera2Session.setCaptureLogger(captureLogger);
        Camera2Controller.setCaptureLogger(captureLogger);
        // 当前项目的 UVC 摄像头存在"预览已镜像但拍照未镜像"导致拍照输出左右翻转。
        // 摄像头型号已确认不再变化，全局开启后置拍照镜像。
        Camera2Session.setGlobalForceBackMirror(true);
        Log.d(TAG, "DualCamera plugin loaded (globalForceBackMirror=true)");
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        shutdownManager();
        if (imageSplitter != null) {
            imageSplitter.shutdown();
            imageSplitter = null;
        }
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        if (cameraManager != null) {
            cameraManager.pausePreview();
        }
        // 兜底：app 切后台 / 进程被暂停时关闭当前日志会话，
        // 防止 onBeforeUnmount 在后台路径下没机会触发导致 session 残留。
        if (captureLogger != null) {
            captureLogger.closeSession();
        }
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        if (cameraManager != null) {
            cameraManager.resumePreview();
        }
    }

    private void shutdownManager() {
        if (cameraManager != null) {
            cameraManager.shutdown();
            cameraManager = null;
        }
        // 关闭摄像头占用时同步关闭日志会话。
        // closeSession 内部有 sessionOpen 守卫，重复调用安全。
        if (captureLogger != null) {
            captureLogger.closeSession();
        }
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @PluginMethod()
    public void getAvailableCameras(PluginCall call) {
        if (!hasCameraPermission()) {
            call.reject("Camera permission not granted");
            return;
        }

        DualCameraManager manager = cameraManager != null ? cameraManager : new DualCameraManager(
                getContext(),
                new DualCameraManager.EventCallback() {
                    @Override
                    public void onCameraError(String error) {}

                    @Override
                    public void onCaptureComplete(String[] uris, String[] paths, long[] fileSizeKb) {}
                }
        );

        manager.getAvailableCameras(new DualCameraManager.AvailableCamerasCallback() {
            @Override
            public void onResult(List<Camera2Session.Camera2Info> cameras) {
                JSObject result = new JSObject();
                JSArray cameraList = new JSArray();
                try {
                    for (Camera2Session.Camera2Info cam : cameras) {
                        JSObject camJson = new JSObject();
                        camJson.put("cameraId", cam.cameraId);
                        camJson.put("lensFacing", cam.lensFacing);
                        camJson.put("previewWidth", cam.previewSize.getWidth());
                        camJson.put("previewHeight", cam.previewSize.getHeight());
                        camJson.put("captureWidth", cam.captureSize.getWidth());
                        camJson.put("captureHeight", cam.captureSize.getHeight());
                        cameraList.put(camJson);
                    }
                    result.put("cameras", cameraList);
                    call.resolve(result);
                } catch (Exception e) {
                    call.reject("Failed to build camera list", e);
                } finally {
                    // 临时 manager 一定释放，避免 Activity 泄漏
                    if (manager != cameraManager) manager.shutdown();
                }
            }

            @Override
            public void onError(String error) {
                call.reject(error);
                if (manager != cameraManager) manager.shutdown();
            }
        });
    }

    @PluginMethod()
    public void isDualCameraSupported(PluginCall call) {
        if (!hasCameraPermission()) {
            call.reject("Camera permission not granted");
            return;
        }

        DualCameraManager manager = cameraManager != null ? cameraManager : new DualCameraManager(
                getContext(),
                new DualCameraManager.EventCallback() {
                    @Override
                    public void onCameraError(String error) {}

                    @Override
                    public void onCaptureComplete(String[] uris, String[] paths, long[] fileSizeKb) {}
                }
        );

        manager.isDualCameraSupported(supported -> {
            JSObject result = new JSObject();
            result.put("supported", supported);
            call.resolve(result);
            // 临时 manager 用完即释放
            if (manager != cameraManager) manager.shutdown();
        });
    }

    @PluginMethod()
    public void startPreview(PluginCall call) {
        Log.d(TAG, "startPreview called");
        if (!hasCameraPermission()) {
            requestAllPermissions(call, "onPermissionResult");
            return;
        }
        startPreviewInternal(call);
    }

    @PermissionCallback
    private void onPermissionResult(PluginCall call) {
        if (hasCameraPermission()) {
            startPreviewInternal(call);
        } else {
            call.reject("Camera permission denied");
        }
    }

    private void startPreviewInternal(PluginCall call) {
        if (cameraManager != null) {
            call.reject("Preview is already running");
            return;
        }

        // 之前这里调过 portReleaseDelegate（让 HiTi USB 让位给 Camera2）。
        // 现在 HiTi 改成页面级 init/release：在没有打印页面的期间 HiTi 不占 USB，
        // Camera2 可以直接拿到 UVC interface，不需要再去 release HiTi。
        // 保留这个注释作为变更说明。

        cameraManager = new DualCameraManager(
                getContext(),
                new DualCameraManager.EventCallback() {
                    @Override
                    public void onCameraError(String error) {
                        JSObject ret = new JSObject();
                        ret.put("error", error);
                        notifyListeners("previewError", ret);
                    }

                    @Override
                    public void onCaptureComplete(String[] uris, String[] paths, long[] fileSizeKb) {
                        JSObject result = new JSObject();
                        for (int i = 0; i < uris.length; i++) {
                            result.put("cameraUrl" + i, uris[i]);
                            result.put("cameraPath" + i, paths[i]);
                            result.put("cameraFileSize" + i, fileSizeKb[i]);
                        }
                        result.put("cameraFileSizeUnit", "KB");
                        result.put("timestamp", System.currentTimeMillis());
                        notifyListeners("captureComplete", result);
                    }
                }
        );

        cameraManager.startPreview(call);

        // 摄像头真正起来时自动开启一次日志会话——
        // 不再依赖 JS 端 onMounted 调 startLogSession，确保只要摄像头被占用就有日志可查。
        // startSession 内部每次创建新文件，多次进入页面不会丢日志。
        if (captureLogger != null) {
            String path = captureLogger.startSession();
            captureLogger.java("DualCameraPlugin", "auto startLogSession -> " + path);
        }
    }

    @PluginMethod()
    public void stopPreview(PluginCall call) {
        if (cameraManager == null) {
            // 之前这里调过 portRestoreDelegate（HiTi USB 监听恢复）。
            // 现在 HiTi 改成页面级 init/release，离开打印页时已经 release，
            // Camera2 期间 HiTi 根本不占 USB；离开 Camera2 时也无需恢复 HiTi。
            call.resolve();
            return;
        }

        cameraManager.stopPreview(() -> {
            cameraManager = null;
            call.resolve();
        });
    }

    @PluginMethod()
    public void copyImageToExternalCache(PluginCall call) {
        String uriString = call.getString("uri");
        Log.d(TAG, "copyImageToExternalCache called, uri=" + uriString + ", data=" + call.getData());
        if (uriString == null || uriString.isEmpty()) {
            call.reject("uri is required");
            return;
        }

        try {
            Uri sourceUri = Uri.parse(uriString);
            File cacheDir = getContext().getExternalCacheDir();
            if (cacheDir == null) {
                call.reject("External cache dir not available");
                return;
            }

            String mimeType = getContext().getContentResolver().getType(sourceUri);
            String extension;
            if (mimeType == null) {
                extension = "jpg";
            } else {
                switch (mimeType) {
                    case "image/png": extension = "png"; break;
                    case "image/gif": extension = "gif"; break;
                    case "image/webp": extension = "webp"; break;
                    default: extension = "jpg";
                }
            }

            File destFile = new File(cacheDir, "display_" + System.currentTimeMillis() + "." + extension);
            FileOutputStream out = new FileOutputStream(destFile);
            InputStream in = getContext().getContentResolver().openInputStream(sourceUri);
            if (in == null) {
                out.close();
                call.reject("Cannot open input stream for uri: " + uriString);
                return;
            }
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            in.close();
            out.close();

            JSObject result = new JSObject();
            result.put("path", destFile.getAbsolutePath());
            // File.toURI() returns "file:/..." (single slash) on some Android versions,
            // which Capacitor.convertFileSrc() fails to recognize. Normalize to "file:///"
            // so the WebView's local server can serve it via http://localhost/_capacitor_file_/...
            String displayUri = "file://" + destFile.getAbsolutePath();
            result.put("uri", displayUri);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to copy image: " + e.getMessage(), e);
        }
    }

    @PluginMethod()
    public void splitImage(PluginCall call) {
        if (imageSplitter == null) {
            imageSplitter = new ImageSplitter(getContext());
        }
        String imageUrl = call.getString("imageUrl");
        Double splitRatio = call.getDouble("splitRatio");
        Integer inset = call.getInt("inset");
        if (imageUrl == null || imageUrl.isEmpty()) {
            call.reject("imageUrl is required");
            return;
        }

        final double ratio = splitRatio != null ? splitRatio : 0.5;
        final int insetPx = inset != null ? inset : 0;
        Log.d(TAG, "splitImage called, ratio=" + ratio + ", inset=" + insetPx);

        imageSplitter.split(imageUrl, ratio, insetPx, new ImageSplitter.SplitCallback() {
            @Override
            public void onSuccess(String leftPath, String rightPath, int leftWidth, int rightWidth, int height) {
                JSObject result = new JSObject();
                result.put("leftUrl", leftPath);
                result.put("rightUrl", rightPath);
                result.put("leftWidth", leftWidth);
                result.put("rightWidth", rightWidth);
                result.put("height", height);
                call.resolve(result);
            }

            @Override
            public void onError(String error) {
                call.reject(error);
            }
        });
    }

    @PluginMethod()
    public void readImageAsBase64(PluginCall call) {
        if (imageSplitter == null) {
            imageSplitter = new ImageSplitter(getContext());
        }
        String input = call.getString("input");
        if (input == null || input.isEmpty()) {
            call.reject("input is required");
            return;
        }
        String b64 = imageSplitter.readImageAsBase64(input);
        if (b64 == null) {
            call.reject("Failed to read image: " + input);
            return;
        }
        JSObject result = new JSObject();
        result.put("base64", b64);
        call.resolve(result);
    }

    @PluginMethod()
    public void clearImageCache(PluginCall call) {
        if (imageSplitter == null) {
            imageSplitter = new ImageSplitter(getContext());
        }
        try {
            int removed = imageSplitter.clearCache();
            JSObject result = new JSObject();
            result.put("removed", removed);
            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "clearImageCache failed", e);
            call.reject("Failed to clear image cache: " + e.getMessage(), e);
        }
    }

    /**
     * 启动一次"拍摄日志会话"。前端在用户进入拍照流程时调用一次，
     * 之后所有 native 层的 Log.d 都同时写入日志文件。返回日志文件路径。
     */
    @PluginMethod()
    public void startLogSession(PluginCall call) {
        String path = captureLogger.startSession();
        if (path == null) {
            call.reject("Failed to start log session");
            return;
        }
        captureLogger.java("DualCameraPlugin", "startLogSession -> " + path);
        JSObject result = new JSObject();
        result.put("path", path);
        call.resolve(result);
    }

    /**
     * 关闭当前日志会话（写 footer）。前端在用户退出拍照流程时调用。
     */
    @PluginMethod()
    public void closeLogSession(PluginCall call) {
        captureLogger.closeSession();
        captureLogger.java("DualCameraPlugin", "closeLogSession called");
        call.resolve();
    }

    /**
     * 前端写一行日志到 native 日志文件（layer=JS）。
     * JS 侧每条 console.log 都会通过这个方法镜像写一份，方便汇总。
     */
    @PluginMethod()
    public void captureLog(PluginCall call) {
        String tag = call.getString("tag", "JS");
        String msg = call.getString("msg", "");
        if (msg.isEmpty()) {
            call.resolve();
            return;
        }
        captureLogger.log("JS", tag, msg);
        call.resolve();
    }

    /**
     * 返回当前日志文件的：
     *   - path: 绝对路径（adb pull / 文件管理器可见）
     *   - uri:  content:// URI（前端 Share API 用）
     *   - size: 文件字节数
     */
    @PluginMethod()
    public void getLogSessionInfo(PluginCall call) {
        JSObject result = new JSObject();
        result.put("path", captureLogger.getCurrentLogFilePath());
        Uri uri = captureLogger.getCurrentLogUri();
        result.put("uri", uri != null ? uri.toString() : null);
        result.put("size", captureLogger.getCurrentLogSize());
        call.resolve(result);
    }

    @PluginMethod()
    public void displayPhotos(PluginCall call) {
        if (cameraManager == null) {
            call.reject("Camera not initialized");
            return;
        }
        JSObject filesObj = call.getObject("files");
        if (filesObj == null) {
            call.reject("files is required");
            return;
        }
        String[] frontPaths = parseStringArray(filesObj, "front");
        String[] backPaths = parseStringArray(filesObj, "back");
        if (frontPaths == null || backPaths == null) {
            call.reject("files.front and files.back are required");
            return;
        }
        cameraManager.displayPhotos(new String[]{frontPaths[0], backPaths[0]});
        call.resolve();
    }

    @PluginMethod()
    public void resumePreviewFromPhotos(PluginCall call) {
        if (cameraManager == null) {
            call.reject("Camera not initialized");
            return;
        }
        cameraManager.resumePreviewFromPhotos();
        call.resolve();
    }

    @PluginMethod()
    public void capture(PluginCall call) {
        if (cameraManager == null) {
            call.reject("Preview is not running. Call startPreview first.");
            return;
        }
        cameraManager.capture(call);
    }

    /**
     * 全局开关：是否对所有后置摄像头的拍照输出做水平镜像。
     * 用于解决 UVC 摄像头"预览已镜像但拍照未镜像"导致的左右翻转问题。
     * 必须在 startPreview 之前调用才能影响本次预览周期。
     */
    @PluginMethod()
    public void setGlobalForceBackMirror(PluginCall call) {
        Boolean v = call.getBoolean("enabled", false);
        Camera2Session.setGlobalForceBackMirror(v != null && v);
        Log.d(TAG, "setGlobalForceBackMirror=" + v);
        JSObject ret = new JSObject();
        ret.put("enabled", v != null && v);
        call.resolve(ret);
    }

    @PluginMethod()
    public void uploadPhotos(PluginCall call) {
        String uploadUrl = call.getString("uploadUrl");
        if (uploadUrl == null || uploadUrl.isEmpty()) {
            call.reject("uploadUrl is required");
            return;
        }

        JSObject filesObj = call.getObject("files");
        if (filesObj == null) {
            call.reject("files is required");
            return;
        }

        Map<String, String[]> files = parseFilesObject(filesObj);
        if (files == null) {
            call.reject("Invalid files format");
            return;
        }

        JSObject extraDataObj = call.getObject("extraData");
        Map<String, String> extraData = parseExtraDataObject(extraDataObj);

        Log.d(TAG, "[DualCamera] uploadPhotos called");
        Log.d(TAG, "[DualCamera]   uploadUrl = " + uploadUrl);
        Log.d(TAG, "[DualCamera]   files fields = " + (files == null ? "null" : files.keySet().toString()));
        Log.d(TAG, "[DualCamera]   extraData = " + (extraData == null ? "null" : extraData.toString()));

        photoUploader.upload(uploadUrl, files, extraData, new PhotoUploader.UploadCallback() {
            @Override
            public void onSuccess(String response) {
                JSObject result = new JSObject();
                result.put("response", response);
                call.resolve(result);
            }

            @Override
            public void onError(String error) {
                call.reject(error);
            }
        });
    }

    private Map<String, String[]> parseFilesObject(JSObject filesObj) {
        Map<String, String[]> files = new HashMap<>();
        try {
            java.util.Iterator<String> fieldIt = filesObj.keys();
            while (fieldIt.hasNext()) {
                String fieldName = fieldIt.next();
                org.json.JSONArray pathsArray = (org.json.JSONArray) filesObj.get(fieldName);
                String[] paths = new String[pathsArray.length()];
                for (int i = 0; i < pathsArray.length(); i++) {
                    paths[i] = pathsArray.getString(i);
                }
                files.put(fieldName, paths);
            }
        } catch (org.json.JSONException e) {
            return null;
        }
        return files;
    }

    private Map<String, String> parseExtraDataObject(JSObject extraDataObj) {
        Map<String, String> extraData = new HashMap<>();
        if (extraDataObj == null) return extraData;
        try {
            java.util.Iterator<String> keysIt = extraDataObj.keys();
            while (keysIt.hasNext()) {
                String key = keysIt.next();
                extraData.put(key, extraDataObj.getString(key));
            }
        } catch (Exception e) {
            // ignore malformed entries
        }
        return extraData;
    }

    private String[] parseStringArray(JSObject obj, String key) {
        try {
            org.json.JSONArray arr = (org.json.JSONArray) obj.get(key);
            if (arr == null || arr.length() == 0) return null;
            String[] result = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                result[i] = arr.getString(i);
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }
}
