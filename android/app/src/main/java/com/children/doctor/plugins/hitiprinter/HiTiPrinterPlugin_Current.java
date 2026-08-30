package com.children.doctor.plugins.hitiprinter;

import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;

/**
 * path-current：单路径强化版本的 Capacitor plugin。
 *
 * <p>与 {@link HiTiPrinterPlugin}（4 个 printMode 多模式）的区别：
 * <ul>
 *   <li>独立类，独立 Capacitor name "HiTiPrinterCurrent"（避免与其它路径冲突）</li>
 *   <li>只用 {@link HiTiPrinterManager_Current}（不接原 HiTiPrinterManager）</li>
 *   <li>{@code printPhoto} 不接 {@code bitmapProcessMode} 参数（已移除）</li>
 *   <li>{@code printPhoto} 默认 paperType=2 (4×6)，matte=1, printCount=1, printMode=0</li>
 * </ul>
 *
 * <p>前端调用方使用方式：
 * <pre>
 * import { HiTiPrinterCurrent } from '@/plugins/hiti-printer-current'
 * await HiTiPrinterCurrent.initForPage()
 * await HiTiPrinterCurrent.startService()
 * const res = await HiTiPrinterCurrent.printPhoto({ base64, paperType: 2 })
 * await HiTiPrinterCurrent.releaseForPage()
 * </pre>
 */
@CapacitorPlugin(name = "HiTiPrinterCurrent")
public class HiTiPrinterPlugin_Current extends Plugin {

    private static final String TAG = "HiTiPrinterPlugin_Current";

    private HiTiPrinterManager_Current manager;
    private PrintLogger printLogger;

    private void logD(String msg) {
        Log.d(TAG, msg);
        if (printLogger != null) printLogger.java(TAG, msg);
    }

    private void logE(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        if (printLogger != null) printLogger.java(TAG, msg + (t == null ? "" : " | " + t));
    }

    private void logE(String msg) {
        Log.e(TAG, msg);
        if (printLogger != null) printLogger.java(TAG, msg);
    }

    @Override
    public void load() {
        super.load();
        printLogger = new PrintLogger(getContext());
        manager = new HiTiPrinterManager_Current(getContext(), printLogger);
        logD("HiTiPrinterCurrent plugin loaded");
    }

    @PluginMethod()
    public void initForPage(PluginCall call) {
        logD("initForPage: called");
        if (manager == null) {
            call.resolve(buildErr("manager_not_initialized"));
            return;
        }
        try {
            manager.init();
            call.resolve(buildOk("init_for_page"));
        } catch (Throwable t) {
            logE("initForPage: failed", t);
            call.resolve(buildErr(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
        }
    }

    @PluginMethod()
    public void releaseForPage(PluginCall call) {
        logD("releaseForPage: called");
        if (manager == null) {
            call.resolve(buildOk("not_initialized"));
            return;
        }
        try {
            manager.release();
            call.resolve(buildOk("released"));
        } catch (Throwable t) {
            logE("releaseForPage: failed", t);
            call.resolve(buildErr(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
        }
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
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
        if (printLogger != null) printLogger.closeSession();
        call.resolve();
    }

    @PluginMethod()
    public void captureLog(PluginCall call) {
        String tag = call.getString("tag", "JS");
        String msg = call.getString("msg", "");
        if (msg.isEmpty()) { call.resolve(); return; }
        if (printLogger != null) printLogger.log("JS", tag, msg);
        call.resolve();
    }

    @PluginMethod()
    public void getPrinterStatus(PluginCall call) {
        manager.getPrinterStatus(new HiTiPrinterManager_Current.Callback<Object>() {
            @Override public void onSuccess(Object payload) {
                call.resolve(buildOk(toStatus(payload)));
            }
            @Override public void onError(String error) {
                call.resolve(buildErr(error));
            }
        });
    }

    @PluginMethod()
    public void getModelName(PluginCall call) { manager.getModelName(simpleString(call)); }
    @PluginMethod()
    public void getSerialNumber(PluginCall call) { manager.getSerialNumber(simpleString(call)); }
    @PluginMethod()
    public void getFirmwareVersion(PluginCall call) { manager.getFirmwareVersion(simpleString(call)); }

    @PluginMethod()
    public void getRibbonInfo(PluginCall call) {
        manager.getRibbonInfo(new HiTiPrinterManager_Current.Callback<int[]>() {
            @Override public void onSuccess(int[] payload) { call.resolve(buildOk(toIntArray(payload))); }
            @Override public void onError(String error) { call.resolve(buildErr(error)); }
        });
    }

    @PluginMethod()
    public void getPrintCount(PluginCall call) {
        manager.getPrintCount(new HiTiPrinterManager_Current.Callback<int[]>() {
            @Override public void onSuccess(int[] payload) { call.resolve(buildOk(toIntArray(payload))); }
            @Override public void onError(String error) { call.resolve(buildErr(error)); }
        });
    }

    @PluginMethod()
    public void resetPrinter(PluginCall call) { manager.resetPrinter(simpleVoid(call)); }
    @PluginMethod()
    public void resumeJob(PluginCall call) { manager.resumeJob(simpleVoid(call)); }
    @PluginMethod()
    public void ejectPaperJam(PluginCall call) { manager.ejectPaperJam(simpleVoid(call)); }

    /**
     * 打印入口（path-current 单路径）。
     * 不再接受 bitmapProcessMode 参数。
     */
    @PluginMethod()
    public void printPhoto(PluginCall call) {
        String base64 = call.getString("base64", "");
        int paperType = call.getInt("paperType", 2);
        int printCount = call.getInt("printCount", 1);
        short matte = (short) call.getInt("matte", 1).intValue();
        short printMode = (short) call.getInt("printMode", 0).intValue();
        logD("[current] printPhoto: paperType=" + paperType
                + " printCount=" + printCount
                + " matte=" + matte
                + " printMode=" + printMode
                + " base64Len=" + (base64 == null ? "null" : base64.length()));
        if (base64 == null || base64.isEmpty()) {
            logE("[current] printPhoto: base64 is null/empty");
            JSObject err = new JSObject();
            err.put("ok", false);
            err.put("error", "base64 is required");
            call.resolve(err);
            return;
        }
        try {
            byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            File cache = getContext().getExternalCacheDir();
            if (cache == null) {
                logE("[current] printPhoto: External cache dir not available");
                JSObject err = new JSObject();
                err.put("ok", false);
                err.put("error", "External cache dir not available");
                call.resolve(err);
                return;
            }
            File out = new File(cache, "hiti_current_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bytes);
            }
            logD("[current] printPhoto: file written, " + out.length() + " bytes");
            HiTiPrinterManager_Current.CurrentPrintOptions opts = new HiTiPrinterManager_Current.CurrentPrintOptions(
                    out.getAbsolutePath(), paperType, printCount, matte, printMode);
            manager.printPhoto(opts, new HiTiPrinterManager_Current.Callback<Object>() {
                @Override public void onSuccess(Object payload) {
                    logD("[current] printPhoto: onSuccess: " + (payload == null ? "null" : payload.toString()));
                    JSObject o = new JSObject();
                    o.put("ok", true);
                    o.put("data", payload == null ? "done" : payload.toString());
                    call.resolve(o);
                }
                @Override public void onError(String error) {
                    logE("[current] printPhoto: onError: " + error);
                    call.resolve(buildErr(error));
                }
            });
        } catch (Throwable t) {
            logE("[current] printPhoto failed", t);
            JSObject err = new JSObject();
            err.put("ok", false);
            err.put("error", t.getMessage() != null ? t.getMessage() : "decode failed");
            call.resolve(err);
        }
    }

    @PluginMethod()
    public void startService(PluginCall call) {
        manager.startService(new HiTiPrinterManager_Current.Callback<com.hiti.usb.service.ErrorCode>() {
            @Override public void onSuccess(com.hiti.usb.service.ErrorCode payload) {
                JSObject o = new JSObject();
                o.put("value", payload != null ? payload.value : 0);
                o.put("description", payload != null ? payload.description : "");
                call.resolve(buildOk(o));
            }
            @Override public void onError(String error) {
                call.resolve(buildErr(error));
            }
        });
    }

    @PluginMethod()
    public void stopService(PluginCall call) {
        manager.stopService(new HiTiPrinterManager_Current.Callback<com.hiti.usb.service.ErrorCode>() {
            @Override public void onSuccess(com.hiti.usb.service.ErrorCode payload) {
                JSObject o = new JSObject();
                o.put("value", payload != null ? payload.value : 0);
                o.put("description", payload != null ? payload.description : "");
                call.resolve(buildOk(o));
            }
            @Override public void onError(String error) {
                call.resolve(buildErr(error));
            }
        });
    }

    // ---- helpers ----

    private HiTiPrinterManager_Current.Callback<String> simpleString(PluginCall call) {
        return new HiTiPrinterManager_Current.Callback<String>() {
            @Override public void onSuccess(String payload) { call.resolve(buildOk(payload == null ? "" : payload)); }
            @Override public void onError(String error) { call.resolve(buildErr(error)); }
        };
    }

    private HiTiPrinterManager_Current.Callback<Object> simpleVoid(PluginCall call) {
        return new HiTiPrinterManager_Current.Callback<Object>() {
            @Override public void onSuccess(Object payload) { call.resolve(buildOk("done")); }
            @Override public void onError(String error) { call.resolve(buildErr(error)); }
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
        Object[] pair = HiTiPrinterManager_Current.safeStatus(retData);
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
}
