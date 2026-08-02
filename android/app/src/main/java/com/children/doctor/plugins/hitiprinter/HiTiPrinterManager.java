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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HiTiPrinterIO");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ServiceConnector serviceConnector;
    private String tablesRoot = "";
    private int nextJobId = 101;

    public HiTiPrinterManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void init() {
        // ServiceConnector.register must run on the main thread.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::init);
            return;
        }
        if (serviceConnector == null) {
            serviceConnector = ServiceConnector.register(context, null);
        }
        // Extract Tables asset directory lazily on the IO thread.
        io.execute(this::ensureTablesExtracted);
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
        if (bitmapPath == null || bitmapPath.isEmpty()) {
            post(cb, null, "bitmapPath is required");
            return;
        }
        // Adapter adapts to the sample's PaperType switch (2..6). Default to 6x4 (2).
        int pt = paperType <= 0 ? 2 : paperType;
        io.execute(() -> {
            try {
                PrinterJob job = new PrinterJob(nextJobId++, Action.USB_PRINT_PHOTOS);
                Object attr = buildPhotoAttr(bitmapPath, pt);
                job.setJobPara(attr);
                if (serviceConnector == null) {
                    post(cb, null, "ServiceConnector not initialized");
                    return;
                }
                serviceConnector.m_strTablesRoot = tablesRoot;
                serviceConnector.doService(job);
                post(cb, job.retData, errorOf(job));
            } catch (Throwable t) {
                Log.e(TAG, "printPhoto failed", t);
                post(cb, null, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            }
        });
    }

    public void startService(Callback<ErrorCode> cb) {
        mainHandler.post(() -> {
            if (serviceConnector == null) {
                post(cb, null, "ServiceConnector not initialized");
                return;
            }
            ErrorCode code = serviceConnector.StartService();
            post(cb, code, code == null ? "StartService returned null" : (code.value == 0 ? null : code.description));
        });
    }

    public void stopService(Callback<ErrorCode> cb) {
        mainHandler.post(() -> {
            if (serviceConnector == null) {
                post(cb, null, "ServiceConnector not initialized");
                return;
            }
            ErrorCode code = serviceConnector.StopService();
            post(cb, code, code == null ? "StopService returned null" : (code.value == 0 ? null : code.description));
        });
    }

    // ---- Internal helpers ----

    private <T> void runOp(Action action, Object data, byte format, Callback<T> cb) {
        io.execute(() -> {
            try {
                if (serviceConnector == null) {
                    post(cb, null, "ServiceConnector not initialized");
                    return;
                }
                PrinterJob job = new PrinterJob(nextJobId++, action);
                if (data != null) {
                    Object para = buildSetPara(action, data);
                    if (para != null) job.setJobPara(para);
                }
                serviceConnector.m_strTablesRoot = tablesRoot;
                serviceConnector.doService(job);
                @SuppressWarnings("unchecked")
                T payload = (T) job.retData;
                post(cb, payload, errorOf(job));
            } catch (Throwable t) {
                Log.e(TAG, "op " + action + " failed", t);
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
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(bitmapPath);
        if (bitmap == null) return null;
        PaperSize size;
        switch (paperType) {
            case 3: size = PaperSize.PAPER_SIZE_5X7_PHOTO; break;
            case 4: size = PaperSize.PAPER_SIZE_6X8_PHOTO; break;
            case 5: size = PaperSize.PAPER_SIZE_6X4_SPLIT_2UP; break;
            case 6: size = PaperSize.PAPER_SIZE_6X6_PHOTO; break;
            case 2:
            default: size = PaperSize.PAPER_SIZE_6X4_PHOTO; break;
        }
        return PrintPara.getPrintPhotoPara(bitmap, (short) 1, (short) 0, (short) 1, size, tablesRoot);
    }

    private String errorOf(PrinterJob job) {
        if (job == null || job.errCode == null) return "Unknown error";
        return job.errCode.value == 0 ? null : job.errCode.description;
    }

    private <T> void post(Callback<T> cb, T payload, String error) {
        mainHandler.post(() -> {
            if (cb == null) return;
            if (error != null) cb.onError(error);
            else cb.onSuccess(payload);
        });
    }

    private void ensureTablesExtracted() {
        try {
            File root = context.getExternalFilesDir(null);
            if (root == null) {
                Log.w(TAG, "ExternalFilesDir null; SDK print will fail");
                return;
            }
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
            for (String name : assets) {
                File out = new File(tables, name);
                if (out.exists()) continue;
                try (InputStream in = am.open(TABLES_ASSET_DIR + "/" + name);
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                } catch (IOException e) {
                    Log.w(TAG, "Failed to extract " + name, e);
                }
            }
            tablesRoot = tables.getAbsolutePath();
            Log.d(TAG, "tablesRoot=" + tablesRoot);
        } catch (Throwable t) {
            Log.e(TAG, "ensureTablesExtracted failed", t);
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