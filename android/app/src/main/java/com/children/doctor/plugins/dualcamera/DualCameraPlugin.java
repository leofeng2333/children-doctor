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

    @Override
    public void load() {
        super.load();
        photoUploader = new PhotoUploader();
        Log.d(TAG, "DualCamera plugin loaded");
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        shutdownManager();
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        if (cameraManager != null) {
            cameraManager.pausePreview();
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
                }
            }

            @Override
            public void onError(String error) {
                call.reject(error);
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
    }

    @PluginMethod()
    public void stopPreview(PluginCall call) {
        if (cameraManager == null) {
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
