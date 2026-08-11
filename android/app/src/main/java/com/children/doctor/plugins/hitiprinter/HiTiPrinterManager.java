package com.children.doctor.plugins.hitiprinter;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.hiti.usb.printer.PrintPara;
import com.hiti.usb.printer.PrintPara.PaperSize;
import com.hiti.usb.printer.PrinterJob;
import com.hiti.usb.printer.PrinterStatus;
import com.hiti.usb.service.Action;
import com.hiti.usb.service.ErrorCode;
import com.hiti.usb.service.ServiceConnector;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Wraps the HiTi (HiTi Inc.) USB photo-printer SDK
 * ({@code printerService-release.aar}) so the Capacitor plugin layer can
 * drive it without leaking SDK types.
 *
 * <p>The wrapper:
 * <ul>
 *   <li>Creates a {@link ServiceConnector} bound to the host activity context.</li>
 *   <li>Lazily extracts the SDK's color table {@code .bin} assets to
 *       {@code getExternalFilesDir(...)/Tables} on first use (mirrors
 *       {@code MainActivity#copyFileOrDir} in the vendor sample).</li>
 *   <li>Exposes the 10 documented operations (see
 *       {@code UsbLinkSdk_Manual} in the SDK release) as plain methods that
 *       return a {@link Result} POJO consumable by
 *       {@link HiTiPrinterPlugin}.</li>
 * </ul>
 *
 * <p>All SDK calls are blocking — every method here runs on a single-thread
 * executor and posts callbacks back to the main thread.
 */
public class HiTiPrinterManager {

    private static final String TAG = "HiTiPrinterManager";
    private static final String TABLES_ASSET_DIR = "Tables";

    private final Context context;
    /** Print session logger; may be null if plugin wasn't initialized properly. */
    private final PrintLogger printLogger;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HiTiPrinterIO");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ServiceConnector serviceConnector;
    private String tablesRoot = "";
    private int nextJobId = 101;
    /** Blocks printPhoto until Tables have been extracted from assets. */
    private final CountDownLatch tablesReady = new CountDownLatch(1);

    public HiTiPrinterManager(Context context, PrintLogger printLogger) {
        this.context = context.getApplicationContext();
        this.printLogger = printLogger;
    }

    /**
     * 同时输出到 logcat 和 PrintLogger（如果已启动会话）。
     * 等价于 Log.d + printLogger.java 的快捷方法。
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

    public void init() {
        logD("init() called, looper=" + Looper.myLooper());
        // ServiceConnector.register must run on the main thread.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::init);
            return;
        }
        if (serviceConnector == null) {
            try {
                serviceConnector = ServiceConnector.register(context, null);
                logD("ServiceConnector.register() OK: " + serviceConnector);
            } catch (Throwable t) {
                logE("ServiceConnector.register() failed", t);
                throw t;
            }
        } else {
            logD("ServiceConnector already registered, skip");
        }
        // Extract Tables asset directory lazily on the IO thread.
        io.execute(this::ensureTablesExtracted);
        logD("init() done, tablesRoot (before extract)='" + tablesRoot + "'");
    }

    public void release() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::release);
            return;
        }
        if (serviceConnector != null) {
            try {
                serviceConnector.unregister();
            } catch (Exception e) {
                Log.w(TAG, "serviceConnector.unregister failed", e);
            }
            serviceConnector = null;
        }
    }

    public void shutdown() {
        io.shutdownNow();
    }

    // ---- SDK ops: each returns Result via callback on the main thread ----

    public void getPrinterStatus(Callback<Object> cb) {
        runOp(Action.USB_CHECK_PRINTER_STATUS, null, (byte) 0, cb);
    }

    public void getModelName(Callback<String> cb) {
        runOp(Action.USB_DEVICE_MODEL_NAME, null, (byte) 0, cb);
    }

    public void getSerialNumber(Callback<String> cb) {
        runOp(Action.USB_DEVICE_SERIAL_NUM, null, (byte) 0, cb);
    }

    public void getFirmwareVersion(Callback<String> cb) {
        runOp(Action.USB_DEVICE_FW_VERSION, null, (byte) 0, cb);
    }

    public void getRibbonInfo(Callback<int[]> cb) {
        runOp(Action.USB_DEVICE_RIBBON_INFO, null, (byte) 0, cb);
    }

    public void getPrintCount(Callback<int[]> cb) {
        runOp(Action.USB_DEVICE_PRINT_COUNT, null, (byte) 0, cb);
    }

    public void resetPrinter(Callback<Object> cb) {
        runOp(Action.USB_COMMAND_RESET_PRINTER, null, (byte) 0, cb);
    }

    public void resumeJob(Callback<Object> cb) {
        runOp(Action.USB_COMMAND_RESUME_JOB, null, (byte) 0, cb);
    }

    public void ejectPaperJam(Callback<Object> cb) {
        runOp(Action.USB_EJECT_PAPER_JAM, null, (byte) 0, cb);
    }

    public void printPhoto(String bitmapPath, int paperType, Callback<Object> cb) {
        logD("printPhoto() called: bitmapPath=" + bitmapPath + " paperType=" + paperType);
        if (bitmapPath == null || bitmapPath.isEmpty()) {
            logE("printPhoto: bitmapPath is empty");
            post(cb, null, "bitmapPath is required");
            return;
        }
        // Adapter adapts to the sample's PaperType switch (2..6). Default to 6x4 (2).
        int pt = paperType <= 0 ? 2 : paperType;
        io.execute(() -> {
            try {
                // Block until Tables are extracted from assets. Without this, tablesRoot
                // is still "" when printPhoto races ahead of ensureTablesExtracted.
                boolean tablesLoaded = tablesReady.await(10, TimeUnit.SECONDS);
                if (!tablesLoaded) {
                    logE("printPhoto: tablesReady timed out after 10s — tablesRoot='" + tablesRoot + "'");
                    post(cb, null, "Tables extraction timed out");
                    return;
                }
                if (tablesRoot == null || tablesRoot.isEmpty()) {
                    logE("printPhoto: tablesRoot is still empty after await — tablesRoot='" + tablesRoot + "'");
                    post(cb, null, "Tables root not set");
                    return;
                }
                logD("printPhoto: tablesReady confirmed, tablesRoot='" + tablesRoot + "'");

                int jobId = nextJobId++;
                PrinterJob job = new PrinterJob(jobId, Action.USB_PRINT_PHOTOS);
                logD("printPhoto: created PrinterJob id=" + jobId);
                Object attr = buildPhotoAttr(bitmapPath, pt);
                if (attr == null) {
                    logE("printPhoto: buildPhotoAttr returned null (bitmapPath=" + bitmapPath + " paperType=" + pt + ")");
                    post(cb, null, "Failed to decode bitmap from " + bitmapPath);
                    return;
                }
                logD("printPhoto: buildPhotoAttr OK, attr=" + attr.getClass().getSimpleName());
                job.setJobPara(attr);
                if (serviceConnector == null) {
                    logE("printPhoto: serviceConnector is null");
                    post(cb, null, "ServiceConnector not initialized");
                    return;
                }
                logD("printPhoto: tablesRoot='" + tablesRoot + "' (set on serviceConnector)");
                serviceConnector.m_strTablesRoot = tablesRoot;
                serviceConnector.doService(job);
                String errStr = errorOf(job);
                String errCodeStr = job.errCode == null ? "null" :
                    "0x" + Integer.toHexString(job.errCode.value) + " desc=" + String.valueOf(job.errCode.description);
                logD("printPhoto: doService returned, errCode=" + errCodeStr + " retData=" + job.retData);

                // Fallback: if the JobCallback never fires (observed on some Android 14
                // / Rockchip devices), resolve after a short timeout using the synchronous
                // errCode as ground truth instead of waiting indefinitely.
                final Callback<Object> callback = cb;
                mainHandler.postDelayed(() -> {
                    logD("printPhoto: fallback timeout fired for jobId=" + jobId);
                    if (callback != null) {
                        callback.onSuccess(errStr == null ? "printed" : null);
                    }
                }, 3000);

                post(new Callback<Object>() {
                    @Override
                    public void onSuccess(Object payload) {
                        mainHandler.removeCallbacksAndMessages(null);
                        if (callback != null) {
                            callback.onSuccess(errStr == null ? "printed" : null);
                        }
                    }
                    @Override
                    public void onError(String error) {
                        mainHandler.removeCallbacksAndMessages(null);
                        if (callback != null) {
                            callback.onError(error);
                        }
                    }
                }, job.retData, errStr);
            } catch (Throwable t) {
                logE("printPhoto failed", t);
                post(cb, null, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            }
        });
    }

    public void startService(Callback<ErrorCode> cb) {
        logD("startService() called, serviceConnector=" + serviceConnector);
        mainHandler.post(() -> {
            if (serviceConnector == null) {
                logE("startService: ServiceConnector is null");
                post(cb, null, "ServiceConnector not initialized");
                return;
            }
            try {
                ErrorCode code = serviceConnector.StartService();
                logD("StartService() returned: " + (code == null ? "null" : "value=0x" + Integer.toHexString(code.value) + " desc=" + code.description));
                post(cb, code, code == null ? "StartService returned null" : (code.value == 0 ? null : code.description));
            } catch (Throwable t) {
                logE("StartService() threw", t);
                post(cb, null, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            }
        });
    }

    public void stopService(Callback<ErrorCode> cb) {
        logD("stopService() called");
        mainHandler.post(() -> {
            if (serviceConnector == null) {
                logE("stopService: ServiceConnector is null");
                post(cb, null, "ServiceConnector not initialized");
                return;
            }
            try {
                ErrorCode code = serviceConnector.StopService();
                logD("StopService() returned: " + (code == null ? "null" : "value=0x" + Integer.toHexString(code.value) + " desc=" + code.description));
                post(cb, code, code == null ? "StopService returned null" : (code.value == 0 ? null : code.description));
            } catch (Throwable t) {
                logE("StopService() threw", t);
                post(cb, null, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            }
        });
    }

    // ---- Internal helpers ----

    private <T> void runOp(Action action, Object data, byte format, Callback<T> cb) {
        logD("runOp() called: action=" + action);
        io.execute(() -> {
            try {
                if (serviceConnector == null) {
                    logE("runOp: serviceConnector is null (action=" + action + ")");
                    post(cb, null, "ServiceConnector not initialized");
                    return;
                }
                int jobId = nextJobId++;
                PrinterJob job = new PrinterJob(jobId, action);
                logD("runOp: jobId=" + jobId + " action=" + action);
                if (data != null) {
                    Object para = buildSetPara(action, data);
                    if (para != null) job.setJobPara(para);
                }
                serviceConnector.m_strTablesRoot = tablesRoot;
                serviceConnector.doService(job);
                String errStr = errorOf(job);
                String errCodeStr = job.errCode == null ? "null" :
                    "0x" + Integer.toHexString(job.errCode.value) + " desc=" + String.valueOf(job.errCode.description);
                logD("runOp: doService returned, errCode=" + errCodeStr + " retData=" + job.retData);
                @SuppressWarnings("unchecked")
                T payload = (T) job.retData;
                post(cb, payload, errStr);
            } catch (Throwable t) {
                logE("op " + action + " failed", t);
                post(cb, null, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            }
        });
    }

    private Object buildSetPara(Action action, Object data) {
        switch (action) {
            case USB_SET_AUTO_POWER_OFF:
                if (data instanceof Short) return PrintPara.getSetCommandPara((Short) data);
                return null;
            default:
                return null;
        }
    }

    private Object buildPhotoAttr(String bitmapPath, int paperType) {
        logD("buildPhotoAttr: bitmapPath=" + bitmapPath + " paperType=" + paperType);
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(bitmapPath);
        if (bitmap == null) {
            logE("buildPhotoAttr: BitmapFactory.decodeFile returned null for " + bitmapPath);
            return null;
        }
        logD("buildPhotoAttr: decoded bitmap " + bitmap.getWidth() + "x" + bitmap.getHeight());
        PaperSize size;
        switch (paperType) {
            case 3: size = PaperSize.PAPER_SIZE_5X7_PHOTO; break;
            case 4: size = PaperSize.PAPER_SIZE_6X8_PHOTO; break;
            case 5: size = PaperSize.PAPER_SIZE_6X4_SPLIT_2UP; break;
            case 6: size = PaperSize.PAPER_SIZE_6X6_PHOTO; break;
            case 2:
            default: size = PaperSize.PAPER_SIZE_6X4_PHOTO; break;
        }
        logD("buildPhotoAttr: PaperSize=" + size);
        Object para = PrintPara.getPrintPhotoPara(bitmap, (short) 1, (short) 0, (short) 1, size, tablesRoot);
        logD("buildPhotoAttr: PrintPara.getPrintPhotoPara returned " + (para == null ? "null" : para.getClass().getSimpleName()));
        return para;
    }

    private String errorOf(PrinterJob job) {
        if (job == null || job.errCode == null) {
            logD("errorOf: job/errCode null");
            return "Unknown error";
        }
        String err = job.errCode.value == 0 ? null : job.errCode.description;
        logD("errorOf: errCode=" + job.errCode.value + " desc=" + job.errCode.description + " → " + String.valueOf(err));
        return err;
    }

    private <T> void post(Callback<T> cb, T payload, String error) {
        mainHandler.post(() -> {
            if (cb == null) return;
            if (error != null) cb.onError(error);
            else cb.onSuccess(payload);
        });
    }

    private void ensureTablesExtracted() {
        logD("ensureTablesExtracted: start");
        try {
            File root = context.getExternalFilesDir(null);
            if (root == null) {
                Log.w(TAG, "ExternalFilesDir null; SDK print will fail");
                return;
            }
            logD("ensureTablesExtracted: externalFilesDir=" + root);
            File tables = new File(root, TABLES_ASSET_DIR);
            if (!tables.exists() && !tables.mkdirs()) {
                Log.w(TAG, "Failed to create tables dir: " + tables);
                return;
            }
            AssetManager am = context.getAssets();
            String[] assets = am.list(TABLES_ASSET_DIR);
            if (assets == null || assets.length == 0) {
                Log.w(TAG, "No tables bundled in assets/" + TABLES_ASSET_DIR);
                return;
            }
            logD("ensureTablesExtracted: found " + assets.length + " tables to extract");
            for (String name : assets) {
                File out = new File(tables, name);
                if (out.exists()) {
                    logD("ensureTablesExtracted: skip existing " + name);
                    continue;
                }
                try (InputStream in = am.open(TABLES_ASSET_DIR + "/" + name);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                    logD("ensureTablesExtracted: extracted " + name + " (" + out.length() + " bytes)");
                } catch (IOException e) {
                    Log.w(TAG, "Failed to extract " + name, e);
                }
            }
            tablesRoot = tables.getAbsolutePath();
            logD("tablesRoot=" + tablesRoot + " — signaling tablesReady");
        } catch (Throwable t) {
            logE("ensureTablesExtracted failed", t);
        } finally {
            tablesReady.countDown();
        }
    }

    public interface Callback<T> {
        void onSuccess(T payload);
        void onError(String error);
    }

    /**
     * Convenience static helper for callers that want to read a String from a
     * {@link PrinterJob} and propagate the standard error fields.
     */
    public static String safeString(Object retData) {
        return retData == null ? "" : retData.toString();
    }

    /**
     * Convert a HiTi {@link PrinterStatus} into a {@code [value, desc]} pair
     * for JSON serialization.
     */
    public static Object[] safeStatus(Object retData) {
        if (retData instanceof PrinterStatus) {
            PrinterStatus s = (PrinterStatus) retData;
            return new Object[] { s.statusValue, s.statusDescription };
        }
        return null;
    }

    /**
     * Convert an SDK IntArray into a {@code int[]} for JSON serialization.
     */
    public static int[] safeIntArray(Object retData) {
        if (retData == null) return new int[0];
        if (retData instanceof int[]) return (int[]) retData;
        try {
            // com.hiti.usb.jni.JniData.IntArray — reflectively pull int[] out
            // without depending on the SDK type at compile time.
            Object arrObj = retData.getClass().getMethod("toIntArray").invoke(retData);
            if (arrObj instanceof int[]) return (int[]) arrObj;
            // Fallback: walk get(i)
            int size = ((Integer) retData.getClass().getMethod("getSize").invoke(retData)).intValue();
            int[] out = new int[size];
            for (int i = 0; i < size; i++) {
                Object v = retData.getClass().getMethod("get", int.class).invoke(retData, i);
                out[i] = v instanceof Integer ? ((Integer) v).intValue() : 0;
            }
            return out;
        } catch (Throwable t) {
            return new int[0];
        }
    }
}