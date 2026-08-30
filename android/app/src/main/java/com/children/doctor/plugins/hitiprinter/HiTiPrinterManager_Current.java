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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * path-current：基于现有打印逻辑（HEAD c6096b8）的单路径强化版本。
 *
 * <p>本类与 {@link HiTiPrinterManager}（4 个 printMode 多模式版本）功能等价，
 * 但去掉了多模式诊断（bitmapProcessMode / portrait-rotate / normalizeBitmapToPaperSize），
 * 改用单一确定性策略：
 *
 * <ul>
 *   <li>paperType=2 → fast path：bitmap 缩放到 1844×1240 物理像素，命中 SDK fast path</li>
 *   <li>其它 paperType → 直接送 SDK，由 SDK 内部按 PaperSize 处理</li>
 *   <li>warmupGate：等 ClockTask 跑够 2 个周期再 doService（防冷启动 stall）</li>
 *   <li>retry 1 次：doService timeout 后等 2s 重试（区分冷启动 vs SDK 真挂）</li>
 * </ul>
 *
 * <p>设计目标：
 * <ol>
 *   <li>消除多模式分支带来的不确定性（"哪个 mode 才正确？"）</li>
 *   <li>让生产路径（ScanSubscription.vue 调 printPhoto）只走一条已验证的路径</li>
 *   <li>保留 ClockTask + sync doService 基础结构（与 v1.0.15-print-stable 一致）</li>
 *   <li>补三个防御性增强：fast path 命中 SDK fast path / warmupGate 防冷启动 / retry 防单次抖动</li>
 * </ol>
 *
 * <p>Capacitor 入口：{@link HiTiPrinterPlugin_Current} 用 "HiTiPrinterCurrent" 注册。
 */
public class HiTiPrinterManager_Current {

    private static final String TAG = "HiTiPrinterManager_Current";
    private static final String TABLES_ASSET_DIR = "Tables";

    private final Context context;
    private final PrintLogger printLogger;

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HiTiCurrentIO");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** doService 专用 executor，避免与 {@link #io} 互锁。 */
    private final ExecutorService printExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HiTiCurrentPrint");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    /** ClockTask keepalive USB control transfer 通道（与 sampleAPK 一致）。 */
    private ScheduledExecutorService clockExecutor;
    private ScheduledFuture<?> clockFuture;
    private static final long CLOCK_INTERVAL_SECONDS = 3L;
    private static final long CLOCK_INITIAL_DELAY_SECONDS = 3L;

    /** warmupGate 状态：ClockTask 完成次数（每次 run 完成时 +1）。 */
    private final Object warmupLock = new Object();
    private int warmupCycleCount = 0;
    /** 冷启动 readiness gate：要求 ClockTask 至少跑 2 个周期 = 6s 才让 printPhoto 进入 doService。 */
    private static final int WARMUP_REQUIRED_CYCLES = 2;
    private static final long WARMUP_POLL_MS = 200L;
    /** warmupGate fail-safe：等 10s 强制放行（避免永久阻塞 UI）。 */
    private static final long WARMUP_MAX_WAIT_MS = 10_000L;
    /** doService timeout（与现有 printPhoto 一致）：20s。 */
    private static final long PRINT_PHOTO_TIMEOUT_SECONDS = 20L;
    /** 状态探测 timeout：5s。 */
    private static final long PRINT_STATUS_TIMEOUT_SECONDS = 5L;
    /** retry 间延迟：2s（约一个 ClockTask 周期，让 USB 通道再被 keepalive 一次）。 */
    private static final long PRINT_RETRY_DELAY_MS = 2000L;
    /** retry 最大次数：1（首次失败后再试 1 次，2 次都失败就报错）。 */
    private static final int PRINT_MAX_RETRIES = 1;
    /** paperType=2 (4×6) fast path 目标物理像素：1844×1240。SDK 注释里 PAPER_SIZE_6X4_PHOTO 期望。 */
    private static final int FAST_PATH_W = 1844;
    private static final int FAST_PATH_H = 1240;

    private ServiceConnector serviceConnector;
    private String tablesRoot = "";
    private int nextJobId = 101;
    private final CountDownLatch tablesReady = new CountDownLatch(1);

    public HiTiPrinterManager_Current(Context context, PrintLogger printLogger) {
        this.context = context.getApplicationContext();
        this.printLogger = printLogger;
    }

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

    public void init() {
        logD("init() called, looper=" + Looper.myLooper());
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
        // 离开打印页面时清零 warmup counter，下次冷启动重新进入 gate
        synchronized (warmupLock) {
            warmupCycleCount = 0;
        }
    }

    public void shutdown() {
        io.shutdownNow();
        printExecutor.shutdownNow();
    }

    // ---- SDK ops ----

    public void getPrinterStatus(Callback<Object> cb) {
        runOp(Action.USB_CHECK_PRINTER_STATUS, null, (byte) 0, PRINT_STATUS_TIMEOUT_SECONDS, cb);
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

    /**
     * 单一路径打印入口（path-current）。
     *
     * <p>策略：
     * <ol>
     *   <li>await tablesReady（表根解压完）</li>
     *   <li>warmupGate（等 ClockTask 跑够 2 个周期，让 USB control transfer 通道热身）</li>
     *   <li>bitmap 解码 → paperType=2 fast path 缩放到 1844×1240</li>
     *   <li>PrintPara.getPrintPhotoPara 装配参数</li>
     *   <li>同步 doService（带 20s timeout 兜底）</li>
     *   <li>失败 → 2s 后重试 1 次</li>
     *   <li>两次都失败 → UI 立即报错</li>
     * </ol>
     *
     * <p>每一步失败都有详细日志记录到 PrintLogger + logcat，方便回溯问题。
     */
    public void printPhoto(final CurrentPrintOptions opts, final Callback<Object> cb) {
        logD("printPhoto() called: " + opts);

        if (opts == null || opts.bitmapPath == null || opts.bitmapPath.isEmpty()) {
            logE("printPhoto: bitmapPath is empty");
            post(cb, null, "bitmapPath is required");
            return;
        }

        Thread sampleThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 1) tablesReady
                    boolean tablesLoaded = tablesReady.await(10, TimeUnit.SECONDS);
                    if (!tablesLoaded) {
                        logE("printPhoto: tablesReady timed out");
                        post(cb, "USB_PRINT_PHOTOS -ID? : err <0x? Tables extraction timed out>", null);
                        return;
                    }
                    if (tablesRoot == null || tablesRoot.isEmpty()) {
                        logE("printPhoto: tablesRoot is empty after await");
                        post(cb, "USB_PRINT_PHOTOS -ID? : err <0x? Tables root not set>", null);
                        return;
                    }

                    // 2) warmupGate：等 ClockTask 跑够 2 个周期
                    warmupGate();

                    // 3) 参数 + bitmap
                    final int PRINTCOUNT = opts.printCount;
                    final short MATTE = opts.matte;
                    final short PRINTMODE = opts.printMode;
                    final int PaperType = opts.paperType;
                    logD("printPhoto: PRINTCOUNT=" + PRINTCOUNT
                            + " MATTE=" + MATTE + " PRINTMODE=" + PRINTMODE
                            + " PaperType=" + PaperType);

                    // 4) bitmap 解码 → paperType=2 fast path
                    android.graphics.Bitmap rawBitmap = android.graphics.BitmapFactory.decodeFile(opts.bitmapPath);
                    if (rawBitmap == null) {
                        logE("printPhoto: BitmapFactory.decodeFile returned null");
                        post(cb, "USB_PRINT_PHOTOS : err <0x? Bitmap decode failed: " + opts.bitmapPath + ">", null);
                        return;
                    }
                    logD("printPhoto: decoded bitmap " + rawBitmap.getWidth() + "x" + rawBitmap.getHeight());

                    PaperSize size = paperTypeToSize(PaperType);
                    android.graphics.Bitmap bitmap = rawBitmap;
                    if (PaperType == 2
                            && (bitmap.getWidth() != FAST_PATH_W || bitmap.getHeight() != FAST_PATH_H)) {
                        logD("printPhoto: [fast path] scaling " + bitmap.getWidth() + "x" + bitmap.getHeight()
                                + " -> " + FAST_PATH_W + "x" + FAST_PATH_H);
                        bitmap = scaleBitmapToFastPath(bitmap);
                    }

                    // 5) PrintPara 装配
                    int jobId = nextJobId++;
                    PrinterJob job = new PrinterJob(jobId, Action.USB_PRINT_PHOTOS);
                    logD("printPhoto: PrinterJob created id=" + jobId);

                    Object para = PrintPara.getPrintPhotoPara(bitmap,
                            (short) PRINTCOUNT, MATTE, PRINTMODE, size, tablesRoot);
                    if (para == null) {
                        logE("printPhoto: PrintPara.getPrintPhotoPara returned null");
                        post(cb, "USB_PRINT_PHOTOS -ID" + jobId
                                + " : err <0x? PrintPara null>", null);
                        return;
                    }
                    job.setJobPara(para);
                    logD("printPhoto: PrintPara assembled, bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight()
                            + " size=" + size);

                    // 6) sync doService (带 20s timeout 兜底 + retry 1 次)
                    int attempt = 0;
                    String lastError = null;
                    while (attempt <= PRINT_MAX_RETRIES) {
                        if (attempt > 0) {
                            logD("printPhoto: retry " + attempt + "/" + PRINT_MAX_RETRIES
                                    + " after " + PRINT_RETRY_DELAY_MS + "ms");
                            try {
                                Thread.sleep(PRINT_RETRY_DELAY_MS);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                logE("printPhoto: retry interrupted");
                                break;
                            }
                            PrinterJob retryJob = new PrinterJob(nextJobId++, Action.USB_PRINT_PHOTOS);
                            retryJob.setJobPara(para);
                            Object[] r = doSyncWithTimeout(retryJob);
                            if (r == null) return;
                            if ((Boolean) r[0]) {
                                post(cb, r[1], null);
                                return;
                            }
                            lastError = (String) r[1];
                            attempt++;
                            continue;
                        }

                        logD("printPhoto: [attempt 0] calling doService synchronously");
                        Object[] r = doSyncWithTimeout(job);
                        if (r == null) return;
                        if ((Boolean) r[0]) {
                            post(cb, r[1], null);
                            return;
                        }
                        lastError = (String) r[1];
                        attempt++;
                    }

                    logE("printPhoto: all " + (PRINT_MAX_RETRIES + 1) + " attempts failed, last error: " + lastError);
                    post(cb, null, lastError != null ? lastError
                            : "USB_PRINT_PHOTOS failed after " + (PRINT_MAX_RETRIES + 1) + " attempts");
                } catch (Throwable t) {
                    logE("printPhoto raw Thread failed", t);
                    post(cb, null, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                }
            }
        }, "HiTi-Current-Print-" + System.currentTimeMillis());
        sampleThread.start();
        logD("printPhoto: raw Thread started");
    }

    /**
     * sync doService + timeout 兜底。返回 {@code [Boolean ok, String dataOrError]}，
     * null 表示 serviceConnector 未初始化（致命错误）。
     */
    private Object[] doSyncWithTimeout(PrinterJob job) {
        if (serviceConnector == null) {
            logE("doSyncWithTimeout: serviceConnector is null");
            post(null, null, "ServiceConnector not initialized");
            return null;
        }
        final String[] result = new String[2];
        final boolean[] done = new boolean[]{false};
        doServiceWithTimeout(job, PRINT_PHOTO_TIMEOUT_SECONDS, new Callback<String>() {
            @Override public void onSuccess(String errStr) {
                synchronized (done) {
                    if (done[0]) return;
                    done[0] = true;
                }
                if (errStr == null) result[1] = "printed";
                else result[0] = errStr;
            }
            @Override public void onError(String error) {
                synchronized (done) {
                    if (done[0]) return;
                    done[0] = true;
                }
                result[0] = error;
            }
        });
        if (result[1] != null) return new Object[]{Boolean.TRUE, result[1]};
        return new Object[]{Boolean.FALSE, result[0]};
    }

    private static PaperSize paperTypeToSize(int paperType) {
        switch (paperType) {
            case 3: return PaperSize.PAPER_SIZE_5X7_PHOTO;
            case 4: return PaperSize.PAPER_SIZE_6X8_PHOTO;
            case 5: return PaperSize.PAPER_SIZE_6X4_SPLIT_2UP;
            case 6: return PaperSize.PAPER_SIZE_6X6_PHOTO;
            case 2:
            default: return PaperSize.PAPER_SIZE_6X4_PHOTO;
        }
    }

    /**
     * paperType=2 fast path 缩放：等比放大 + center-crop 到 1844×1240。
     * 比例一致 → 等比缩放；不一致 → 长边对齐 + 中心裁切。
     */
    private android.graphics.Bitmap scaleBitmapToFastPath(android.graphics.Bitmap src) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        if (srcW <= 0 || srcH <= 0) return src;

        float srcRatio = (float) srcW / (float) srcH;
        float targetRatio = (float) FAST_PATH_W / (float) FAST_PATH_H; // ≈ 1.487

        if (Math.abs(srcRatio - targetRatio) < 0.01f) {
            android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(
                    src, FAST_PATH_W, FAST_PATH_H, true);
            logD("scaleBitmapToFastPath: ratio match, scaled " + srcW + "x" + srcH
                    + " -> " + FAST_PATH_W + "x" + FAST_PATH_H);
            return scaled;
        }

        int scaleW, scaleH;
        if (srcRatio > targetRatio) {
            scaleH = FAST_PATH_H;
            scaleW = Math.round(srcW * ((float) FAST_PATH_H / srcH));
        } else {
            scaleW = FAST_PATH_W;
            scaleH = Math.round(srcH * ((float) FAST_PATH_W / srcW));
        }
        android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(src, scaleW, scaleH, true);
        int sx = (scaleW - FAST_PATH_W) / 2;
        int sy = (scaleH - FAST_PATH_H) / 2;
        android.graphics.Bitmap cropped = android.graphics.Bitmap.createBitmap(scaled, sx, sy, FAST_PATH_W, FAST_PATH_H);
        logD("scaleBitmapToFastPath: center-cropped " + srcW + "x" + srcH
                + " -> scaled " + scaleW + "x" + scaleH
                + " -> cropped " + FAST_PATH_W + "x" + FAST_PATH_H);
        if (cropped != scaled) scaled.recycle();
        return cropped;
    }

    public void startService(Callback<ErrorCode> cb) {
        logD("startService() called");
        mainHandler.post(() -> {
            if (serviceConnector == null) {
                logE("startService: ServiceConnector is null");
                post(cb, null, "ServiceConnector not initialized — call initForPage first");
                return;
            }
            try {
                ErrorCode code = serviceConnector.StartService();
                logD("StartService() returned: " + (code == null ? "null" : "value=0x" + Integer.toHexString(code.value) + " desc=" + code.description));
                startClockTask();
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
                stopClockTask();
                post(cb, code, code == null ? "StopService returned null" : (code.value == 0 ? null : code.description));
            } catch (Throwable t) {
                logE("StopService() threw", t);
                post(cb, null, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            }
        });
    }

    private void startClockTask() {
        stopClockTask();
        clockExecutor = Executors.newSingleThreadScheduledExecutor();
        clockFuture = clockExecutor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    if (serviceConnector == null || tablesRoot == null || tablesRoot.isEmpty()) {
                        logD("[ClockTask] skipped: serviceConnector=" + serviceConnector
                                + " tablesRoot='" + tablesRoot + "'");
                        return;
                    }
                    PrinterJob job = new PrinterJob(nextJobId++, Action.USB_CHECK_PRINTER_STATUS);
                    serviceConnector.m_strTablesRoot = tablesRoot;
                    serviceConnector.doService(job);
                    logD("[ClockTask] getPrinterStatus done: errCode="
                            + (job.errCode == null ? "null" : "0x" + Integer.toHexString(job.errCode.value)));
                    serviceConnector.m_strTablesRoot = "";
                    // 递增 warmupCycleCount，触发 warmupGate
                    synchronized (warmupLock) {
                        warmupCycleCount++;
                        warmupLock.notifyAll();
                    }
                } catch (Throwable t) {
                    logE("[ClockTask] getPrinterStatus threw", t);
                }
            }
        }, CLOCK_INITIAL_DELAY_SECONDS, CLOCK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        logD("startClockTask: scheduled, interval=" + CLOCK_INTERVAL_SECONDS + "s");
    }

    private void stopClockTask() {
        if (clockFuture != null) {
            clockFuture.cancel(false);
            clockFuture = null;
        }
        if (clockExecutor != null) {
            clockExecutor.shutdownNow();
            clockExecutor = null;
        }
    }

    /**
     * 冷启动 readiness gate：等 ClockTask 至少跑 {@link #WARMUP_REQUIRED_CYCLES} 次。
     * fail-safe：10s 后强制放行，避免永久阻塞 UI。
     */
    private void warmupGate() {
        if (tablesRoot == null || tablesRoot.isEmpty()) {
            logD("warmupGate: tablesRoot not ready, skipping");
            return;
        }
        if (clockFuture == null || clockFuture.isCancelled()) {
            logD("warmupGate: ClockTask not running, skipping gate");
            return;
        }
        long deadline = System.currentTimeMillis() + WARMUP_MAX_WAIT_MS;
        synchronized (warmupLock) {
            while (warmupCycleCount < WARMUP_REQUIRED_CYCLES) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    logE("warmupGate: deadline " + WARMUP_MAX_WAIT_MS + "ms reached, count=" + warmupCycleCount
                            + " required=" + WARMUP_REQUIRED_CYCLES + " — proceeding anyway");
                    return;
                }
                try {
                    warmupLock.wait(Math.min(remain, WARMUP_POLL_MS));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    logE("warmupGate: interrupted");
                    return;
                }
            }
        }
        logD("warmupGate: passed, cycleCount=" + warmupCycleCount + " >= required=" + WARMUP_REQUIRED_CYCLES);
    }

    // ---- Internal helpers ----

    private <T> void runOp(Action action, Object data, byte format, Callback<T> cb) {
        runOp(action, data, format, PRINT_PHOTO_TIMEOUT_SECONDS, cb);
    }

    private <T> void runOp(Action action, Object data, byte format, long timeoutSeconds, Callback<T> cb) {
        logD("runOp() called: action=" + action + " timeout=" + timeoutSeconds + "s");
        io.execute(() -> {
            try {
                if (serviceConnector == null) {
                    logE("runOp: serviceConnector is null (action=" + action + ")");
                    post(cb, null, "ServiceConnector not initialized");
                    return;
                }
                int jobId = nextJobId++;
                PrinterJob job = new PrinterJob(jobId, action);
                if (data != null) {
                    Object para = buildSetPara(action, data);
                    if (para != null) job.setJobPara(para);
                }
                serviceConnector.m_strTablesRoot = tablesRoot;
                doServiceWithTimeout(job, timeoutSeconds, new Callback<String>() {
                    @Override public void onSuccess(String errStr) {
                        @SuppressWarnings("unchecked")
                        T payload = (T) job.retData;
                        post(cb, payload, errStr);
                    }
                    @Override public void onError(String error) {
                        post(cb, null, error);
                    }
                });
            } catch (Throwable t) {
                logE("op " + action + " failed", t);
                post(cb, null, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            }
        });
    }

    private void doServiceWithTimeout(final PrinterJob job, final long timeoutSeconds, final Callback<String> cb) {
        final String opName = "doService(" + job.action + ", jobId=" + job.getId() + ")";
        Future<String> future = printExecutor.submit(new Callable<String>() {
            @Override public String call() {
                serviceConnector.doService(job);
                return errorOf(job);
            }
        });
        try {
            String errStr = future.get(timeoutSeconds, TimeUnit.SECONDS);
            cb.onSuccess(errStr);
        } catch (TimeoutException te) {
            String errCodeStr = job.errCode == null ? "null" :
                    "0x" + Integer.toHexString(job.errCode.value) + " desc=" + String.valueOf(job.errCode.description);
            logE("doServiceWithTimeout: " + opName + " timed out after " + timeoutSeconds + "s, errCode=" + errCodeStr, te);
            future.cancel(true);
            cb.onError("HiTi " + job.action + " timed out after " + timeoutSeconds + "s (jobId=" + job.getId() + ")");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            logE("doServiceWithTimeout: " + opName + " threw", cause);
            cb.onError(cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logE("doServiceWithTimeout: interrupted " + opName, ie);
            future.cancel(true);
            cb.onError("Interrupted while waiting for " + job.action);
        }
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
        logD("ensureTablesExtracted: start");
        try {
            File root = context.getExternalFilesDir(null);
            if (root == null) {
                Log.w(TAG, "ExternalFilesDir null");
                return;
            }
            File tables = new File(root, TABLES_ASSET_DIR);
            if (!tables.exists() && !tables.mkdirs()) {
                Log.w(TAG, "Failed to create tables dir");
                return;
            }
            AssetManager am = context.getAssets();
            String[] assets = am.list(TABLES_ASSET_DIR);
            if (assets == null || assets.length == 0) {
                Log.w(TAG, "No tables in assets/" + TABLES_ASSET_DIR);
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
            logD("tablesRoot=" + tablesRoot);
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

    /** path-current 单路径打印参数：移除 printMode / bitmapProcessMode 多模式字段。 */
    public static class CurrentPrintOptions {
        public final String bitmapPath;
        public final int paperType;
        public final int printCount;
        public final short matte;
        public final short printMode;

        public CurrentPrintOptions(String bitmapPath, int paperType,
                                   int printCount, short matte, short printMode) {
            this.bitmapPath = bitmapPath;
            this.paperType = paperType;
            this.printCount = printCount;
            this.matte = matte;
            this.printMode = printMode;
        }

        @Override
        public String toString() {
            return "CurrentPrintOptions{bitmapPath='" + bitmapPath + "' paperType=" + paperType
                    + " printCount=" + printCount + " matte=" + matte + " printMode=" + printMode + "}";
        }
    }

    public static String safeString(Object retData) {
        return retData == null ? "" : retData.toString();
    }

    public static Object[] safeStatus(Object retData) {
        if (retData instanceof PrinterStatus) {
            PrinterStatus s = (PrinterStatus) retData;
            return new Object[]{s.statusValue, s.statusDescription};
        }
        return null;
    }

    public static int[] safeIntArray(Object retData) {
        if (retData == null) return new int[0];
        if (retData instanceof int[]) return (int[]) retData;
        try {
            Object arrObj = retData.getClass().getMethod("toIntArray").invoke(retData);
            if (arrObj instanceof int[]) return (int[]) arrObj;
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
