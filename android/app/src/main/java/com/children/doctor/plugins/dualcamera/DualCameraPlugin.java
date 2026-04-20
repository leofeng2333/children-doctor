package com.children.doctor.plugins.dualcamera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(name = "DualCamera", permissions = {
    @Permission(alias = "camera", strings = Manifest.permission.CAMERA)
})
public class DualCameraPlugin extends Plugin {

    private static final String TAG = "DualCameraPlugin";

    private DualCameraManager cameraManager;

    @Override
    public void load() {
        super.load();
        Log.d(TAG, "DualCamera plugin loaded");
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        if (cameraManager != null) {
            cameraManager.stopPreview();
            cameraManager = null;
        }
    }

    private boolean hasNativeCameraPermission() {
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;
    }

    @PluginMethod()
    public void checkPermissions(PluginCall call) {
        JSObject result = new JSObject();
        String state = hasNativeCameraPermission() ? "granted" : "denied";
        result.put("camera", state);
        call.resolve(result);
    }

    @PluginMethod()
    public void requestPermissions(PluginCall call) {
        if (hasNativeCameraPermission()) {
            JSObject result = new JSObject();
            result.put("camera", "granted");
            call.resolve(result);
            return;
        }

        requestAllPermissions(call, "onPermissionResult");
    }

    @PermissionCallback
    private void onPermissionResult(PluginCall call) {
        JSObject result = new JSObject();
        String state = hasNativeCameraPermission() ? "granted" : "denied";
        result.put("camera", state);
        call.resolve(result);
    }

    @PluginMethod()
    public void startPreviewWithPermission(PluginCall call) {
        if (hasNativeCameraPermission()) {
            startPreviewInternal(call);
        } else {
            requestAllPermissions(call, "onPreviewPermissionResult");
        }
    }

    @PermissionCallback
    private void onPreviewPermissionResult(PluginCall call) {
        if (hasNativeCameraPermission()) {
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
            getActivity(),
            new DualCameraManager.PreviewCallback() {
                @Override
                public void onError(String error) {
                    JSObject ret = new JSObject();
                    ret.put("error", error);
                    notifyListeners("previewError", ret);
                }

                @Override
                public void onCaptureComplete(String frontUri, String backUri, String frontPath, String backPath, long frontFileSizeKb, long backFileSizeKb) {
                    JSObject result = new JSObject();
                    result.put("frontCameraUrl", frontUri);
                    result.put("backCameraUrl", backUri);
                    result.put("frontCameraPath", frontPath);
                    result.put("backCameraPath", backPath);
                    result.put("frontCameraFileSize", frontFileSizeKb);
                    result.put("backCameraFileSize", backFileSizeKb);
                    result.put("frontCameraFileSizeUnit", "KB");
                    result.put("backCameraFileSizeUnit", "KB");
                    result.put("timestamp", System.currentTimeMillis());
                    notifyListeners("captureComplete", result);
                }
            }
        );

        cameraManager.startPreview(call);
    }

    @PluginMethod()
    public void startPreview(PluginCall call) {
        Log.d(TAG, "startPreview called, native permission check: " + hasNativeCameraPermission());
        if (!hasNativeCameraPermission()) {
            call.reject("Camera permission not granted");
            return;
        }
        startPreviewInternal(call);
    }

    @PluginMethod()
    public void stopPreview(PluginCall call) {
        if (cameraManager == null) {
            call.reject("Preview is not running");
            return;
        }

        cameraManager.stopPreview();
        cameraManager = null;
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
    public void isPreviewRunning(PluginCall call) {
        JSObject result = new JSObject();
        result.put("running", cameraManager != null);
        call.resolve(result);
    }
}
