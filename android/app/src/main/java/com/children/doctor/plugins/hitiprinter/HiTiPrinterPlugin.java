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
        // 只创建 manager + printLogger；不在 plugin load 时 bind HiTi ServiceConnector
        // 或监听 USB。实测 HiTi SDK 启动后会立刻通过 attach 监听主动 claim 自己
        // 的 USB interface（VID=0x0D16），导致同一 USB controller 上的 UVC camera
        // 在 Camera2 openCamera 时拿到 ERROR_IN_USE，预览起不来。
        // 现在改为页面级 init：进入打印页面时 {@link #initForPage}；离开时
        // {@link #releaseForPage}。app 启动和其它页面（截图、摄像头预览）期间
        // HiTi 不占 USB，UVC camera 完全可用。
        printLogger = new PrintLogger(getContext());
        manager = new HiTiPrinterManager(getContext(), printLogger);
        logD("HiTiPrinter plugin loaded (lazy init deferred to print-page entry)");
    }

    /**
     * 页面级 init：让打印页面（PrintTestView / DetailAnalysisView 内嵌 ScanSubscription）
     * 进入前调一次，bind HiTi ServiceConnector + 注册 USB attach/detach 监听。
     * 等价于旧版 plugin load() 时的自动 init，但延迟到页面级，
     * 让其它无关页面（特别是 CameraCaptureView）不被 HiTi USB claim 影响。
     */
    @PluginMethod()
    public void initForPage(PluginCall call) {
        logD("initForPage: called (page-level HiTi init, will claim USB interface)");
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

    /**
     * 页面级 release：离开打印页面时调一次，unbind HiTi ServiceConnector
     * + 注销 USB attach/detach 监听 + 释放 USB interface。释放后 Camera2
     * service 可以再次拿到 UVC interface，不会再出现端口冲突。
     */
    @PluginMethod()
    public void releaseForPage(PluginCall call) {
        logD("releaseForPage: called (releasing HiTi USB so other pages can use USB bus)");
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

    /**
     * HiTi 打印入口（合并自原 printPhotoBase64 + printPhotoSample）。
     * 接受 base64 JPEG，解码后写到 ExternalCacheDir/hiti_print_*.jpg 调用
     * {@link HiTiPrinterManager#printPhoto}（sample 行为）。返回值是
     * native 侧 {@code retrieveSampleData} 拼出来的字符串（与 SDK 协议一致）。
     *
     * <p>历史：早先有两个方法
     * <ul>
     *   <li>{@code printPhotoBase64} → {@code manager.printPhoto(bitmapPath, paperType)}
     *       —— 走 io executor；测试中冷启第一次 doService 经常卡 20s 兜底超时</li>
     *   <li>{@code printPhotoSample} → {@code manager.printPhotoSample(SamplePrintOptions)}
     *       —— 走 raw {@code new Thread()}；冷启也能 6s 出图</li>
     * </ul>
     * 实测 sample 路径更稳定，因此合并为唯一入口。打印参数（MATTE/PRINTCOUNT/PRINTMODE）
     * 默认值与 sample MainActivity 一致：MATTE=1（覆膜）、PRINTCOUNT=1、PRINTMODE=0。
     */
    @PluginMethod()
    public void printPhoto(PluginCall call) {
        String base64 = call.getString("base64", "");
        int paperType = call.getInt("paperType", 2);
        int printCount = call.getInt("printCount", 1);
        // call.getInt(...) 返回 Integer（boxed），不能直接 (short) 强转；
        // 这里先 intValue() 拆箱再 cast 到 short，与 SamplePrintOptions.short 字段对齐。
        short matte = (short) call.getInt("matte", 1).intValue();
        short printMode = (short) call.getInt("printMode", 0).intValue();
        logD("[sample] printPhoto: paperType=" + paperType
                + " printCount=" + printCount
                + " matte=" + matte
                + " printMode=" + printMode
                + " base64Len=" + (base64 == null ? "null" : base64.length()));
        if (base64 == null || base64.isEmpty()) {
            logE("[sample] printPhoto: base64 is null/empty");
            JSObject err = new JSObject();
            err.put("ok", false);
            err.put("error", "base64 is required");
            call.resolve(err);
            return;
        }
        try {
            byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            logD("[sample] printPhoto: decoded " + bytes.length + " bytes");
            File cache = getContext().getExternalCacheDir();
            if (cache == null) {
                logE("[sample] printPhoto: External cache dir not available");
                JSObject err = new JSObject();
                err.put("ok", false);
                err.put("error", "External cache dir not available");
                call.resolve(err);
                return;
            }
            File out = new File(cache, "hiti_print_" + System.currentTimeMillis() + ".jpg");
            logD("[sample] printPhoto: writing to " + out.getAbsolutePath());
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bytes);
            }
            logD("[sample] printPhoto: file written, " + out.length() + " bytes; calling manager.printPhoto");
            HiTiPrinterManager.SamplePrintOptions opts = new HiTiPrinterManager.SamplePrintOptions(
                    out.getAbsolutePath(), paperType, printCount, matte, printMode);
            manager.printPhoto(opts, new HiTiPrinterManager.Callback<java.lang.Object>() {
                @Override public void onSuccess(Object payload) {
                    logD("[sample] printPhoto: onSuccess: " + (payload == null ? "null" : payload.toString()));
                    // data 字段是 native 端 retrieveSampleData 拼出来的字符串
                    // （成功时不带 errCode，失败时同步带 errCode）。
                    JSObject o = new JSObject();
                    o.put("ok", true);
                    o.put("data", payload == null ? "done" : payload.toString());
                    call.resolve(o);
                }
                @Override public void onError(String error) {
                    logE("[sample] printPhoto: onError: " + error);
                    call.resolve(buildErr(error));
                }
            });
        } catch (Throwable t) {
            logE("[sample] printPhoto failed", t);
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