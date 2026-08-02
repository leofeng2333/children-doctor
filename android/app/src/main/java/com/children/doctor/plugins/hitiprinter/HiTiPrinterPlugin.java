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

    @Override
    public void load() {
        super.load();
        manager = new HiTiPrinterManager(getContext());
        manager.init();
        Log.d(TAG, "HiTiPrinter plugin loaded");
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        if (manager != null) {
            manager.release();
            manager.shutdown();
            manager = null;
        }
    }

    @PluginMethod()
    public void getPrinterStatus(PluginCall call) {
        manager.getPrinterStatus(new HiTiPrinterManager.Callback<java.lang.Object>() {
            @Override public void onSuccess(Object payload) {
                call.resolve(buildOk(toStatus(payload)));
            }
            @Override public void onError(String error) {
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
        if (base64 == null || base64.isEmpty()) {
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
                JSObject err = new JSObject();
                err.put("ok", false);
                err.put("error", "External cache dir not available");
                call.resolve(err);
                return;
            }
            File out = new File(cache, "hiti_print_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bytes);
            }
            manager.printPhoto(out.getAbsolutePath(), paperType, simpleVoid(call));
        } catch (Throwable t) {
            Log.e(TAG, "printPhotoBase64 failed", t);
            JSObject err = new JSObject();
            err.put("ok", false);
            err.put("error", t.getMessage() != null ? t.getMessage() : "decode failed");
            call.resolve(err);
        }
    }

    @PluginMethod()
    public void startService(PluginCall call) {
        manager.startService(new HiTiPrinterManager.Callback<com.hiti.usb.service.ErrorCode>() {
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
        manager.stopService(new HiTiPrinterManager.Callback<com.hiti.usb.service.ErrorCode>() {
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
                call.resolve(buildOk("done"));
            }
            @Override public void onError(String error) {
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