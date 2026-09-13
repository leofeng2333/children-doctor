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
 * 但去掉了多模式诊断（bitmapProcessMode / normalizeBitmapToPaperSize），
 * 改用单一确定性策略：
 *
 * <ul>
 *   <li>letterbox fit-within + SAFE_MARGIN_PX：把任意输入 bitmap 等比缩放到 PaperSize
 *       物理像素框的安全区内（四周扣 24 像素 ≈ 1.9mm），居中放在白色画布上 →
 *       按图片本身比例打印 + 四周至少留一点白边做缓冲处理</li>
 *   <li>orientation adapt：图片朝向与相纸朝向不一致（portrait vs landscape）→ 旋转 90°
 *       让两者方向一致后再 letterbox。不裁切图片内容，只在方向对齐后按比例适配，
 *       保证图片在打印区内居中且无白边浪费。square（6×6 纸或正方形图）不需要旋转。</li>
 *   <li>warmupGate（count>=1 + ClockTask initialDelay=0L）：把 sampleAPK"用户停顿 + 弹对话框 +
 *       选图 + 确认"约 3~5s 的隐式 timing 窗口显式化为代码同步门，~50~200ms 内放行</li>
 *   <li>pauseClockTask during print：warmupGate 通过后挂起 ClockTask，避免与 print doService
 *       并发向同一个 serviceConnector 发 USB control transfer（27c3fb0 已实测并发会卡 25s）</li>
 *   <li>retry 1 次：doService timeout 后等 2s 重试（区分冷启动 vs SDK 真挂）</li>
 * </ul>
 *
 * <p>设计目标：
 * <ol>
 *   <li>消除多模式分支带来的不确定性（"哪个 mode 才正确？"）</li>
 *   <li>让生产路径（ScanSubscription.vue 调 printPhoto）只走一条已验证的路径</li>
 *   <li>保留 ClockTask + sync doService 基础结构（与 v1.0.15-print-stable 一致）</li>
 *   <li>补四个防御性增强：letterbox + 安全边距 / warmupGate 显式化 sampleAPK timing /
 *       pauseClockTask 防 27c3fb0 竞态 / retry 防单次抖动</li>
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

    /** ClockTask keepalive USB control transfer 通道（与 sampleAPK 一致）。
     *  sampleAPK 走的是"用户停顿 + 弹对话框 + 选图 + 确认"自然 timing 窗口约 3~5s，期间 ClockTask 至少跑 1 次。
     *  path-current 自动化该 timing：initialDelay=0L，让 ClockTask 在 StartService 成功后立刻跑第 1 次，
     *  warmupGate 等 count>=1（~50~200ms）后即放行，把 sampleAPK 的隐式 timing 窗口显式化为代码同步门。 */
    private ScheduledExecutorService clockExecutor;
    private ScheduledFuture<?> clockFuture;
    private static final long CLOCK_INTERVAL_SECONDS = 3L;
    private static final long CLOCK_INITIAL_DELAY_SECONDS = 0L;

    /** warmupGate 状态：ClockTask 完成次数（每次 run 完成时 +1）。 */
    private final Object warmupLock = new Object();
    private int warmupCycleCount = 0;
    /** ClockTask 暂停标志：printPhoto 期间挂起 ClockTask，避免与 print doService 并发向 serviceConnector
     *  发 USB control transfer（27c3fb0 已实测：ClockTask 与 print doService 并发会让 doService 卡 25s）。
     *  暂停用 wait/notify 在 Runnable 内挂起 run()，不破坏 ClockTask 的 fixedRate 调度时序。 */
    private final Object clockPauseLock = new Object();
    private volatile boolean clockTaskPaused = false;
    /** 冷启动 readiness gate：要求 ClockTask 至少跑 1 次（与 sampleAPK 一致：ClockTask 跑 1 次再点 print）。
     *  配 ClockTask initialDelay=0L，Gate 实际通过时间 ~50~200ms（一次 getPrinterStatus USB transfer 耗时）。 */
    private static final int WARMUP_REQUIRED_CYCLES = 1;
    private static final long WARMUP_POLL_MS = 100L;
    /** warmupGate fail-safe：等 3s 强制放行（ClockTask initialDelay=0 后正常应 ~200ms 内跑完 1 次，
     *  3s 留给 ClockTask 自身卡死的 worst case；比 10s 更短，因为我们已经把 initialDelay 从 3s 改到 0L，
     *  不需要再为初始延迟留 6s 余量）。 */
    private static final long WARMUP_MAX_WAIT_MS = 3_000L;
    /** doService timeout（与现有 printPhoto 一致）：20s。 */
    private static final long PRINT_PHOTO_TIMEOUT_SECONDS = 20L;
    /** 状态探测 timeout：5s。 */
    private static final long PRINT_STATUS_TIMEOUT_SECONDS = 5L;
    /** retry 间延迟：2s（约一个 ClockTask 周期，让 USB 通道再被 keepalive 一次）。 */
    private static final long PRINT_RETRY_DELAY_MS = 2000L;
    /** retry 最大次数：1（首次失败后再试 1 次，2 次都失败就报错）。 */
    private static final int PRINT_MAX_RETRIES = 1;
    /**
     * 各 paperType 对应的 PaperSize 物理像素（SDK 注释里 PAPER_SIZE_*_PHOTO 的"pixels"列）。
     * letterboxBitmapToPaper 把任意输入 bitmap 缩放到该尺寸 + 居中放白底，保证：
     * (1) bitmap 严格等于 PaperSize 物理像素 → SDK 行为可预期，避免冷启动卡死的可能诱因；
     * (2) 等比缩放 + 不裁剪 → 按图片本身比例打印；
     * (3) 四周白边（少量）→ 用户接受。
     */
    private static final int[] PAPER_TYPE_2_PIXELS = {1240, 1844}; // 设计稿 100×150mm 竖版画布（overlay builder 排版尺寸）；
                                                                      // 送 SDK 前由 printPhoto() 顺时针旋转 90° 变 1844×1240 landscape，
                                                                      // 匹配 PAPER_SIZE_6X4_PHOTO。HiTi SDK 没有 4×6 竖版单图枚举。
    private static final int[] PAPER_TYPE_3_PIXELS = {1548, 2140}; // PAPER_SIZE_5X7_PHOTO
    private static final int[] PAPER_TYPE_4_PIXELS = {1844, 2434}; // PAPER_SIZE_6X8_PHOTO
    private static final int[] PAPER_TYPE_5_PIXELS = {1240, 1844}; // PAPER_SIZE_6X4_SPLIT_2UP
    private static final int[] PAPER_TYPE_6_PIXELS = {1844, 1844}; // PAPER_SIZE_6X6_PHOTO
    /**
     * 安全边距（单边，物理像素）：24 像素 ≈ 1.9mm @ 307dpi。
     *
     * <p>letterboxBitmapToPaper 把图片缩放到 {@code (targetW - 2*SAFE_MARGIN_PX, targetH - 2*SAFE_MARGIN_PX)}
     * 安全区，居中放在 PaperSize 物理像素白底画布上。这样即使 bitmap 宽高比与 PaperSize 完全匹配，
     * 四周也至少有 24 像素（约 1.9mm）白边——防止打印到纸张不可印区 / 缓冲 / 用户视觉缓冲。
     *
     * <p>24 像素 ≈ 1.3%~1.9% 短边比例，肉眼几乎不可见，但确实留出了纸张打印工业实践建议的 1.5~2mm 缓冲。
     */
    private static final int SAFE_MARGIN_PX = 24;

    private ServiceConnector serviceConnector;
    private String tablesRoot = "";
    private int nextJobId = 101;
    private final CountDownLatch tablesReady = new CountDownLatch(1);
    /** 打印叠加图层构建器（主图 + QR + icon + 文案 + 珊瑚橙渐变背景，详见该类 javadoc）。
     *  在 printPhoto 开头由 bitmap 直接生成 paper-sized 合成图，SDK 收到的不再是裸主图。 */
    private final HiTiPrintOverlayBuilder overlayBuilder;

    public HiTiPrinterManager_Current(Context context, PrintLogger printLogger) {
        this.context = context.getApplicationContext();
        this.printLogger = printLogger;
        this.overlayBuilder = new HiTiPrintOverlayBuilder(context, printLogger);
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
        // pause 状态跟随 ClockTask 一起重置，避免下次 startService 后 ClockTask 立即恢复却仍处于暂停态
        synchronized (clockPauseLock) {
            clockTaskPaused = false;
            clockPauseLock.notifyAll();
        }
    }

    public void shutdown() {
        io.shutdownNow();
        printExecutor.shutdownNow();
    }

    /**
     * 只合成 overlay（不发送给 SDK），用于"先看效果，确认后再打印"的预览通道。
     *
     * <p>路径与 {@link #printPhoto} 的 step 1-4.5 完全一致：
     * tablesReady / warmupGate / pauseClockTask / bitmap 解码 / overlay 合成。
     * 不同：跳过 PrintPara 装配 + doService + retry + resumeClockTask。
     *
     * <p>注意：此方法不在 io executor，而是同步阻塞调用（composing 是纯 bitmap 操作，
     * 不涉及 SDK USB transfer，~100~300ms）。调用方应该在合适的线程（plugin 内部已
     * 是 Capacitor worker thread）。
     *
     * @throws IOException overlay 合成失败（asset 缺失、bitmap 解码失败、IO 等）
     */
    public java.io.File composeOverlayOnly(String inputPath, int paperType) throws IOException {
        logD("composeOverlayOnly: paperType=" + paperType + " inputPath=" + inputPath);
        // 与 printPhoto 一致：等待 tablesReady，但 compose 本身不需要 ClockTask / warmupGate，
        // 这里只在 tablesRoot 已就绪时调 composeAndWriteToCache，否则直接抛错。
        if (tablesRoot == null || tablesRoot.isEmpty()) {
            throw new IOException("composeOverlayOnly: Tables root not ready (call initForPage + startService first?)");
        }
        return overlayBuilder.composeAndWriteToCache(inputPath, paperType);
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
     *   <li>warmupGate：等 ClockTask 至少跑 1 次（initialDelay=0L 后通常 ~50~200ms 完成），
     *       fail-safe 3s 强制放行</li>
     *   <li>pauseClockTask：暂停 ClockTask，避免与 print doService 并发向同一个
     *       serviceConnector 发 USB control transfer（27c3fb0 实测并发会卡 25s）</li>
     *   <li>bitmap 解码 → letterbox fit-within：等比缩放到 PaperSize 物理像素 + 居中白底
     *       （保持宽高比 + 接受少量白边）</li>
     *   <li>PrintPara.getPrintPhotoPara 装配参数</li>
     *   <li>同步 doService（带 20s timeout 兜底）</li>
     *   <li>失败 → 2s 后重试 1 次（ClockTask 仍暂停）</li>
     *   <li>两次都失败 → finally 中 resumeClockTask，UI 立即报错</li>
     * </ol>
     *
     * <p>每一步失败都有详细日志记录到 PrintLogger + logcat，方便回溯问题。
     * ClockTask 暂停/恢复成对出现：即使任何路径抛异常，finally 也会 resumeClockTask。
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
                boolean clockPausedByUs = false;
                java.io.File overlayFile = null;
                android.graphics.Bitmap rawBitmap = null;
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

                    // 2) warmupGate：等 ClockTask 至少跑 1 次（fail-safe 3s）
                    warmupGate();

                    // 2.5) pauseClockTask：ClockTask 持续运行会与 print doService 并发向
                    // serviceConnector 发 USB control transfer，27c3fb0 实测会卡 25s。
                    // 这里显式暂停 ClockTask，让 print doService 独占 serviceConnector。
                    pauseClockTask();
                    clockPausedByUs = true;

                    // 3) 参数 + bitmap
                    final int PRINTCOUNT = opts.printCount;
                    final short MATTE = opts.matte;
                    final short PRINTMODE = opts.printMode;
                    final int PaperType = opts.paperType;
                    logD("printPhoto: PRINTCOUNT=" + PRINTCOUNT
                            + " MATTE=" + MATTE + " PRINTMODE=" + PRINTMODE
                            + " PaperType=" + PaperType);

                    // 4) 主图 bitmap 解码（仅用于做 overlay 合成素材 + finally 中兜底回收）。
                    // 使用外层 try 上方声明的 rawBitmap，避免作用域遮蔽导致 finally 看不到引用。
                    rawBitmap = android.graphics.BitmapFactory.decodeFile(opts.bitmapPath);
                    if (rawBitmap == null) {
                        logE("printPhoto: BitmapFactory.decodeFile returned null");
                        post(cb, "USB_PRINT_PHOTOS : err <0x? Bitmap decode failed: " + opts.bitmapPath + ">", null);
                        return;
                    }
                    logD("printPhoto: decoded bitmap " + rawBitmap.getWidth() + "x" + rawBitmap.getHeight());

                    // 4.5) overlay 合成：主图 + QR (qrcode.jpg) + icon (home-icon.png) + 文案
                    //      + 珊瑚橙垂直渐变背景 → paper-sized 临时文件
                    //      (ExternalCacheDir/hiti_overlay_<ts>.jpg)。
                    //      SDK 通过 file path 读盘。本次 print 走完（无论成功失败），
                    //      finally 块中 HiTiPrintOverlayBuilder.deleteQuietly 删除。
                    PaperSize size = paperTypeToSize(PaperType);
                    logD("printPhoto: [overlay] composing PaperSize=" + size + " (paperType=" + PaperType + ")");
                    try {
                        overlayFile = overlayBuilder.composeAndWriteToCache(opts.bitmapPath, PaperType);
                    } catch (Throwable t) {
                        logE("printPhoto: overlay compose failed", t);
                        post(cb, "USB_PRINT_PHOTOS : err <0x? Overlay compose failed: "
                                + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()) + ">",
                                null);
                        return;
                    }
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(
                            overlayFile.getAbsolutePath());
                    // 释放原始主图 bitmap（overlay 合成已经把原图绘制到 paper-sized canvas 上）。
                    if (rawBitmap != null && !rawBitmap.isRecycled()) {
                        rawBitmap.recycle();
                    }
                    rawBitmap = null; // 已回收，清空引用 → finally 跳过兜底 recycle
                    if (bitmap == null) {
                        logE("printPhoto: overlay bitmap decode failed: " + overlayFile.getAbsolutePath());
                        post(cb, "USB_PRINT_PHOTOS : err <0x? Overlay bitmap decode failed: "
                                + overlayFile.getAbsolutePath() + ">", null);
                        return;
                    }
                    logD("printPhoto: [overlay] composed " + bitmap.getWidth() + "x" + bitmap.getHeight()
                            + " (paper-sized bitmap ready for SDK, skipping orientation adapt + letterbox)");

                    // 4.6) portrait → landscape 旋转（仅 paperType=2 路径需要）。
                    //      HiTi 4×6 纸盒只支持横版进纸，SDK 的 PAPER_SIZE_6X4_PHOTO 期望 1844×1240 landscape
                    //      bitmap，而 overlay builder 是按设计稿 100×150mm 竖版 1240×1844 排版（image 在上、QR+文
                    //      案在下）。这里在送 SDK 前把 portrait overlay 顺时针旋转 90°，得到 1844×1240 landscape，
                    //      命中 SDK fast path。用户拿到手把 4×6 相纸顺时针旋转 90° 立起来看，布局与设计稿一致。
                    //      其他 paperType（3/4/5/6）保持 portrait / square 原状不动。
                    if (PaperType == 2 && bitmap.getWidth() < bitmap.getHeight()) {
                        android.graphics.Matrix rotMatrix = new android.graphics.Matrix();
                        rotMatrix.postRotate(90f);
                        android.graphics.Bitmap rotated = android.graphics.Bitmap.createBitmap(
                                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), rotMatrix, true);
                        bitmap.recycle();
                        bitmap = rotated;
                        logD("printPhoto: [rotate] paperType=2 portrait overlay rotated 90° CW → "
                                + bitmap.getWidth() + "x" + bitmap.getHeight()
                                + " (matches PAPER_SIZE_6X4_PHOTO fast path)");
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

                    // 6) sync doService (带 20s timeout 兜底 + retry 1 次，ClockTask 整段保持暂停)
                    int attempt = 0;
                    String lastError = null;
                    while (attempt <= PRINT_MAX_RETRIES) {
                        if (attempt > 0) {
                            logD("printPhoto: retry " + attempt + "/" + PRINT_MAX_RETRIES
                                    + " after " + PRINT_RETRY_DELAY_MS + "ms (ClockTask still paused)");
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

                        logD("printPhoto: [attempt 0] calling doService synchronously (ClockTask paused)");
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
                } finally {
                    // 成对出现：无论成功 / 失败 / 异常，都确保 ClockTask 恢复 keepalive，
                    // 否则 ClockTask 会一直挂起，下一次 print 进来时 warmupGate 检测到
                    // count>=1 会立即通过但 ClockTask 已暂停（空跑），失去 keepalive 意义。
                    if (clockPausedByUs) {
                        resumeClockTask();
                    }
                    // 清理 overlay 临时文件（4.5 步骤写到 ExternalCacheDir/hiti_overlay_<ts>.jpg）：
                    // 不论 print 成功 / 失败 / 抛异常，都必须删除。deleteQuietly 内部已 null-safe。
                    HiTiPrintOverlayBuilder.deleteQuietly(overlayFile);
                    // 兜底回收 rawBitmap：4.5 步骤成功路径上已经 recycle + 清空引用，
                    // 这里仅防御 finally 兜底（在 4 步 decode 成功但 4.5 步未执行前就跳到 finally 的极端路径）。
                    if (rawBitmap != null && !rawBitmap.isRecycled()) {
                        rawBitmap.recycle();
                    }
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
            default: return PaperSize.PAPER_SIZE_6X4_PHOTO; // paperType=2 = 4×6 单图（设计稿 100×150mm 竖版；
                                                              // HiTi 4×6 纸盒只支持横版进纸，SDK 把 1240×1844 portrait
                                                              // overlay 在 printPhoto 里旋转 90° 后变 1844×1240 landscape，
                                                              // 命中 PAPER_SIZE_6X4_PHOTO 的 fast path。用户拿到手
                                                              // 顺时针转 90° 立起来看，布局与设计稿 1:1 一致。）
        }
    }

    /**
     * paperType → PaperSize 物理像素查表。返回的 {@code {w, h}} 是
     * {@link #letterboxBitmapToPaper} 的目标尺寸，与 {@link #paperTypeToSize} 的 PaperSize 一一对应。
     * 未知 paperType 走 paperType=2 默认（4×6 物理像素）。
     */
    private static int[] paperTypeToTargetPixels(int paperType) {
        switch (paperType) {
            case 3: return PAPER_TYPE_3_PIXELS;
            case 4: return PAPER_TYPE_4_PIXELS;
            case 5: return PAPER_TYPE_5_PIXELS;
            case 6: return PAPER_TYPE_6_PIXELS;
            case 2:
            default: return PAPER_TYPE_2_PIXELS;
        }
    }

    /**
     * Letterbox fit-within + 安全边距：等比缩放 {@code src} 到完全在
     * {@code (targetW - 2*SAFE_MARGIN_PX, targetH - 2*SAFE_MARGIN_PX)} 安全区内，
     * 居中放在 {@code targetW × targetH}（PaperSize 物理像素）的白色画布上。
     *
     * <p>行为：
     * <ul>
     *   <li>安全区 = PaperSize 物理像素四周各扣 {@link #SAFE_MARGIN_PX}（默认 24 像素 ≈ 1.9mm）</li>
     *   <li>等比缩放 src 到完全在安全区内（min 缩放保证短边不超框，图片完整）</li>
     *   <li>居中放在白色画布上，四周至少有 SAFE_MARGIN_PX 白边（即使宽高比完全匹配）</li>
     *   <li>缩放后尺寸与 src 相同时复用 src（避免 createScaledBitmap 的内存分配）</li>
     * </ul>
     *
     * <p>为什么 letterbox 而不是 center-crop：
     * <ul>
     *   <li>用户要求"按图片本身的比例打印 + 接受白边"——letterbox 同时满足两个条件</li>
     *   <li>center-crop 会裁掉图片的两侧/上下，破坏完整图片，不满足"按比例打印"语义</li>
     * </ul>
     *
     * <p>为什么不在 SDK 内部做 letterbox：
     * <ul>
     *   <li>SDK 闭源，无法验证内部 letterbox 行为</li>
     *   <li>bitmap 严格等于 PaperSize 物理像素时 SDK 行为可预期，可能也是 27c3fb0
     *       冷启动卡死问题的避雷点</li>
     * </ul>
     *
     * <p>为什么需要 SAFE_MARGIN_PX：
     * <ul>
     *   <li>用户要求"打印纸至少留一点白边做缓冲处理"——防止内容打印到纸张物理边缘</li>
     *   <li>HiTi 物理像素 = 理论可印区，实际可印区略小（纸张裁切误差 + 打印机进纸偏移）</li>
     *   <li>24 像素 ≈ 1.9mm @ 307dpi，照片纸工业实践建议值，肉眼几乎不可见</li>
     * </ul>
     *
     * <p>返回的 bitmap 由 SDK 自行 recycle（sampleAPK 也是直接传给 SDK 不显式 recycle）。
     */
    private android.graphics.Bitmap letterboxBitmapToPaper(android.graphics.Bitmap src, int targetW, int targetH) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) {
            logE("letterboxBitmapToPaper: invalid size src=" + srcW + "x" + srcH
                    + " target=" + targetW + "x" + targetH);
            return src;
        }

        // 有效打印区（安全区）：PaperSize 物理像素四周各扣 SAFE_MARGIN_PX
        int safeW = Math.max(1, targetW - 2 * SAFE_MARGIN_PX);
        int safeH = Math.max(1, targetH - 2 * SAFE_MARGIN_PX);

        // 等比缩放到安全区内：min 保证短边不超框，图片完整 + 四周至少 SAFE_MARGIN_PX 白边
        float scale = Math.min((float) safeW / (float) srcW, (float) safeH / (float) srcH);
        int scaledW = Math.max(1, Math.round((float) srcW * scale));
        int scaledH = Math.max(1, Math.round((float) srcH * scale));
        // 缩放前后尺寸相同 → 复用 src，避免 createScaledBitmap 的内存分配
        android.graphics.Bitmap scaled = (scaledW == srcW && scaledH == srcH)
                ? src
                : android.graphics.Bitmap.createScaledBitmap(src, scaledW, scaledH, true);

        // 白色画布（ARGB_8888 质量，与 sampleAPK 默认 BitmapFactory.decodeStream 一致）。
        // 即使 src == targetW × targetH（已匹配物理像素），也要走白底以保留 SAFE_MARGIN_PX 安全边距。
        android.graphics.Bitmap canvas = android.graphics.Bitmap.createBitmap(
                targetW, targetH, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(canvas);
        c.drawColor(android.graphics.Color.WHITE);

        // 居中绘制：dx/dy 至少 SAFE_MARGIN_PX
        int dx = (targetW - scaledW) / 2;
        int dy = (targetH - scaledH) / 2;
        c.drawBitmap(scaled, dx, dy, null);

        if (scaled != src) scaled.recycle();

        logD("letterboxBitmapToPaper: src=" + srcW + "x" + srcH
                + " ratio=" + String.format(java.util.Locale.ROOT, "%.3f", (float) srcW / srcH)
                + " -> scaled=" + scaledW + "x" + scaledH
                + " on " + targetW + "x" + targetH + " white canvas"
                + " (margin=" + SAFE_MARGIN_PX + "px safeArea=" + safeW + "x" + safeH
                + " dx=" + dx + " dy=" + dy
                + " border=(" + dx + "," + dy
                + "," + (targetW - scaledW - dx) + "," + (targetH - scaledH - dy) + "))");
        return canvas;
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
                // pauseClockTask() 期间的 fixedRate tick 在这里挂起，避免 ClockTask 与 print doService 并发
                // 向同一个 serviceConnector 发 USB control transfer（27c3fb0 同类竞态：会卡 25s）。
                synchronized (clockPauseLock) {
                    while (clockTaskPaused) {
                        try {
                            clockPauseLock.wait();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            logD("[ClockTask] interrupted while paused, exiting run()");
                            return;
                        }
                    }
                }
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
        logD("startClockTask: scheduled, initialDelay=" + CLOCK_INITIAL_DELAY_SECONDS
                + "s interval=" + CLOCK_INTERVAL_SECONDS + "s");
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
        // stopClockTask 之后 ClockTask 调度已停，pause/resume 状态不再有意义，重置避免遗留
        synchronized (clockPauseLock) {
            clockTaskPaused = false;
        }
    }

    /**
     * 暂停 ClockTask：让后续 fixedRate tick 进入 Runnable 后立即挂起，不向 serviceConnector 发 USB 命令。
     * 仅挂起 {@code run()}，不取消调度，{@link #resumeClockTask()} 后下一个 tick 立即生效。
     */
    private void pauseClockTask() {
        synchronized (clockPauseLock) {
            clockTaskPaused = true;
        }
        logD("pauseClockTask: ClockTask paused (next tick will hang at wait())");
    }

    /**
     * 恢复 ClockTask：唤醒所有挂起在 {@code clockPauseLock.wait()} 上的 tick，让 ClockTask 继续每 3s 探测。
     */
    private void resumeClockTask() {
        synchronized (clockPauseLock) {
            clockTaskPaused = false;
            clockPauseLock.notifyAll();
        }
        logD("resumeClockTask: ClockTask resumed");
    }

    /**
     * 冷启动 readiness gate：等 ClockTask 至少跑 {@link #WARMUP_REQUIRED_CYCLES} 次。
     * 配 ClockTask initialDelay=0L，正常情况下 ~50~200ms 内通过（一次 getPrinterStatus USB transfer 耗时）。
     * fail-safe：3s 后强制放行（避免 ClockTask 自身卡死时永久阻塞 UI）。
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
