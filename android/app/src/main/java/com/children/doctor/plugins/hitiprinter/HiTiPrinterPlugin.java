package com.children.doctor.plugins.hitiprinter;

import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Capacitor bridge for the HiTi (HiTi Inc.) USB photo-printer SDK.
 *
 * <p>Mirrors the documentation in {@code UsbLinkSdk_Manual} (10 USB commands).
 * Each {@link PluginMethod} maps to exactly one SDK operation and returns
 * {@code { ok: true, data: ... }} on success or {@code { ok: false, error: ... }}
 * on failure. Returning a structured object (rather than {@code call.resolve/reject})
 * keeps the TS side free of try/catch boilerplate and lets {@code utils/print.ts}
 * do a single-shot auto-fallback when the SDK is unavailable.
 *
 * <p>All methods here are async — the underlying SDK calls are blocking and
 * run on a dedicated single-thread executor inside {@link HiTiPrinterManager}.
 */
@CapacitorPlugin(name = "HiTiPrinter")
public class HiTiPrinterPlugin extends Plugin {

    private static final String TAG = "HiTiPrinterPlugin";

    private HiTiPrinterManager manager;
    private PrintLogger printLogger;

    /**
     * 同时输出到 logcat 和 PrintLogger（如果已启动会话）。
     */
    private void logD(String msg) {
        Log.d(TAG, msg);
        if (printLogger != null) printLogger.java(TAG, msg);
    }

    /**
     * 同时输出到 logcat (Log.e) 和 PrintLogger。
     */
    private void logE(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        if (printLogger != null) printLogger.java(TAG, msg + (t == null ? "" : " | " + t));
    }

    /**
     * 同时输出到 logcat (Log.e) 和 PrintLogger（无 throwable 重载）。
     */
    private void logE(String msg) {
        Log.e(TAG, msg);
        if (printLogger != null) printLogger.java(TAG, msg);
    }

    @Override
    public void load() {
        super.load();
        printLogger = new PrintLogger(getContext());
        manager = new HiTiPrinterManager(getContext(), printLogger);
        manager.init();
        logD("HiTiPrinter plugin loaded");
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        // 关闭当前会话（如果有）
        if (printLogger != null) {
            printLogger.closeSession();
            printLogger.shutdown();
            printLogger = null;
        }
        if (manager != null) {
            manager.release();
            manager.shutdown();
            manager = null;
        }
    }

    @PluginMethod()
    public void startLogSession(PluginCall call) {
        if (printLogger == null) {
            call.reject("PrintLogger not initialized");
            return;
        }
        String path = printLogger.startSession();
        if (path == null) {
            call.reject("Failed to start log session");
            return;
        }
        logD("startLogSession -> " + path);
        JSObject result = new JSObject();
        result.put("path", path);
        call.resolve(result);
    }

    @PluginMethod()
    public void closeLogSession(PluginCall call) {
        if (printLogger != null) {
            printLogger.closeSession();
        }
        logD("closeLogSession called");
        call.resolve();
    }

    /**
     * 前端写一行日志到 native 日志文件（layer=JS）。
     */
    @PluginMethod()
    public void captureLog(PluginCall call) {
        String tag = call.getString("tag", "JS");
        String msg = call.getString("msg", "");
        if (msg.isEmpty()) {
            call.resolve();
            return;
        }
        if (printLogger != null) {
            printLogger.log("JS", tag, msg);
        }
        call.resolve();
    }

    @PluginMethod()
    public void getPrinterStatus(PluginCall call) {
        logD("getPrinterStatus: called");
        manager.getPrinterStatus(new HiTiPrinterManager.Callback<java.lang.Object>() {
            @Override public void onSuccess(Object payload) {
                logD("getPrinterStatus: onSuccess, payload=" + (payload == null ? "null" : payload.getClass().getSimpleName()));
                call.resolve(buildOk(toStatus(payload)));
            }
            @Override public void onError(String error) {
                logE("getPrinterStatus: onError: " + error);
                call.resolve(buildErr(error));
            }
        });
    }

    @PluginMethod()
    public void getModelName(PluginCall call) {
        manager.getModelName(simpleString(call));
    }

    @PluginMethod()
    public void getSerialNumber(PluginCall call) {
        manager.getSerialNumber(simpleString(call));
    }

    @PluginMethod()
    public void getFirmwareVersion(PluginCall call) {
        manager.getFirmwareVersion(simpleString(call));
    }

    @PluginMethod()
    public void getRibbonInfo(PluginCall call) {
        manager.getRibbonInfo(new HiTiPrinterManager.Callback<int[]>() {
            @Override public void onSuccess(int[] payload) {
                call.resolve(buildOk(toIntArray(payload)));
            }
            @Override public void onError(String error) {
                call.resolve(buildErr(error));
            }
        });
    }

    @PluginMethod()
    public void getPrintCount(PluginCall call) {
        manager.getPrintCount(new HiTiPrinterManager.Callback<int[]>() {
            @Override public void onSuccess(int[] payload) {
                call.resolve(buildOk(toIntArray(payload)));
            }
            @Override public void onError(String error) {
                call.resolve(buildErr(error));
            }
        });
    }

    @PluginMethod()
    public void resetPrinter(PluginCall call) {
        manager.resetPrinter(simpleVoid(call));
    }

    @PluginMethod()
    public void resumeJob(PluginCall call) {
        manager.resumeJob(simpleVoid(call));
    }

    @PluginMethod()
    public void ejectPaperJam(PluginCall call) {
        manager.ejectPaperJam(simpleVoid(call));
    }

    @PluginMethod()
    public void printPhoto(PluginCall call) {
        String bitmapPath = call.getString("bitmapPath", "");
        int paperType = call.getInt("paperType", 2);
        logD("printPhoto: bitmapPath=" + bitmapPath + " paperType=" + paperType);
        manager.printPhoto(bitmapPath, paperType, simpleVoid(call));
    }

    /**
     * Variant that accepts base64 JPEG bytes directly. Saves the caller
     * (TS side) from installing {@code @capacitor/filesystem} just to
     * materialise a temp file.
     */
    @PluginMethod()
    public void printPhotoBase64(PluginCall call) {
        String base64 = call.getString("base64", "");
        int paperType = call.getInt("paperType", 2);
        logD("printPhotoBase64: paperType=" + paperType + " base64Len=" + (base64 == null ? "null" : base64.length()));
        if (base64 == null || base64.isEmpty()) {
            logE("printPhotoBase64: base64 is null/empty");
            JSObject err = new JSObject();
            err.put("ok", false);
            err.put("error", "base64 is required");
            call.resolve(err);
            return;
        }
        try {
            byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            logD("printPhotoBase64: decoded " + bytes.length + " bytes");
            File cache = getContext().getExternalCacheDir();
            if (cache == null) {
                logE("printPhotoBase64: External cache dir not available");
                JSObject err = new JSObject();
                err.put("ok", false);
                err.put("error", "External cache dir not available");
                call.resolve(err);
                return;
            }
            File out = new File(cache, "hiti_print_" + System.currentTimeMillis() + ".jpg");
            logD("printPhotoBase64: writing to " + out.getAbsolutePath());
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bytes);
            }
            logD("printPhotoBase64: file written, " + out.length() + " bytes; calling manager.printPhoto");
            manager.printPhoto(out.getAbsolutePath(), paperType, simpleVoid(call));
        } catch (Throwable t) {
            logE("printPhotoBase64 failed", t);
            JSObject err = new JSObject();
            err.put("ok", false);
            err.put("error", t.getMessage() != null ? t.getMessage() : "decode failed");
            call.resolve(err);
        }
    }

    @PluginMethod()
    public void startService(PluginCall call) {
        logD("startService: called");
        manager.startService(new HiTiPrinterManager.Callback<com.hiti.usb.service.ErrorCode>() {
            @Override public void onSuccess(com.hiti.usb.service.ErrorCode payload) {
                logD("startService: onSuccess, value=0x" + Integer.toHexString(payload != null ? payload.value : 0) + " desc=" + (payload != null ? payload.description : ""));
                JSObject o = new JSObject();
                o.put("value", payload != null ? payload.value : 0);
                o.put("description", payload != null ? payload.description : "");
                call.resolve(buildOk(o));
            }
            @Override public void onError(String error) {
                logE("startService: onError: " + error);
                call.resolve(buildErr(error));
            }
        });
    }

    @PluginMethod()
    public void stopService(PluginCall call) {
        logD("stopService: called");
        manager.stopService(new HiTiPrinterManager.Callback<com.hiti.usb.service.ErrorCode>() {
            @Override public void onSuccess(com.hiti.usb.service.ErrorCode payload) {
                logD("stopService: onSuccess, value=0x" + Integer.toHexString(payload != null ? payload.value : 0) + " desc=" + (payload != null ? payload.description : ""));
                JSObject o = new JSObject();
                o.put("value", payload != null ? payload.value : 0);
                o.put("description", payload != null ? payload.description : "");
                call.resolve(buildOk(o));
            }
            @Override public void onError(String error) {
                logE("stopService: onError: " + error);
                call.resolve(buildErr(error));
            }
        });
    }

    // ---- helpers ----

    private HiTiPrinterManager.Callback<String> simpleString(PluginCall call) {
        return new HiTiPrinterManager.Callback<String>() {
            @Override public void onSuccess(String payload) {
                call.resolve(buildOk(payload == null ? "" : payload));
            }
            @Override public void onError(String error) {
                call.resolve(buildErr(error));
            }
        };
    }

    private HiTiPrinterManager.Callback<Object> simpleVoid(PluginCall call) {
        return new HiTiPrinterManager.Callback<Object>() {
            @Override public void onSuccess(Object payload) {
                logD("simpleVoid.onSuccess: " + (payload == null ? "null" : payload.toString()));
                call.resolve(buildOk("done"));
            }
            @Override public void onError(String error) {
                logE("simpleVoid.onError: " + error);
                call.resolve(buildErr(error));
            }
        };
    }

    private static JSObject buildOk(Object data) {
        JSObject o = new JSObject();
        o.put("ok", true);
        if (data != null) o.put("data", data);
        return o;
    }

    private static JSObject buildErr(String error) {
        JSObject o = new JSObject();
        o.put("ok", false);
        o.put("error", error == null ? "unknown error" : error);
        return o;
    }

    private static JSObject toStatus(Object retData) {
        Object[] pair = HiTiPrinterManager.safeStatus(retData);
        if (pair == null) return new JSObject();
        JSObject o = new JSObject();
        o.put("statusValue", pair[0]);
        o.put("statusDescription", pair[1]);
        return o;
    }

    private static JSArray toIntArray(int[] arr) {
        JSArray a = new JSArray();
        if (arr == null) return a;
        for (int v : arr) a.put(v);
        return a;
    }

    // Suppress unused-import warning — kept for future expansion.
    @SuppressWarnings("unused")
    private static void touch(JSONArray a) {}
}