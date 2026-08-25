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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    /**
     * doService 的专用 executor，与 {@link #io} 严格分离。
     *
     * <p>历史教训：早期曾用 {@code io.submit(...)} 包 doService、再让
     * {@code io.execute(...)} 外层做 {@code future.get(timeout)}。
     * 结果是死锁 —— 外层任务占着单线程 {@code io} 的唯一线程，内层 submit
     * 排在它后面，永远等不到执行，{@code get(timeout)} 永不返回，UI 永久卡死。
     *
     * <p>修法：专池专用。{@code io} 继续跑 printPhoto 的 prep/cleanup，
     * {@link #printExecutor} 专门跑阻塞的 doService；timeout 与 cancel
     * 都只影响 printExecutor，不阻塞 {@code io}。
     */
    private final ExecutorService printExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HiTiPrinterPrint");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private ServiceConnector serviceConnector;
    private String tablesRoot = "";
    private int nextJobId = 101;
    /** Blocks printPhoto until Tables have been extracted from assets. */
    private final CountDownLatch tablesReady = new CountDownLatch(1);
    /**
     * doService(USB_PRINT_PHOTOS) 在 rockchip / SDK 34 上偶尔会卡死不再返回
     * （见 print_logs/print_20260811_*.log：日志停在 "tablesRoot=... (set on
     * serviceConnector)" 后再无任何打印输出，整条调用链挂死直到 session 结束）。
     * 这时 TS 端 await 永远不会 resolve，UI 卡死。
     *
     * 这里给打印 job 一个兜底超时：超时则强制回调失败，避免 UI 永久挂起。
     * 20s 在一次正常 4x6 打印实测时间（5~15s）之上留余量，又不至于让用户等太久。
     */
    private static final long PRINT_PHOTO_TIMEOUT_SECONDS = 20L;
    /**
     * 状态探测（USB_CHECK_PRINTER_STATUS 等）专用超时：5 秒。
     *
     * <p>为什么比打印短：状态探测的目的就是"快速判断打印机是否在线"。HiTi SDK
     * 没有"不调 USB 命令就能预检设备在不在"的轻量级 API，{@code getPrinterStatus}
     * 本质上也是发一个 USB control transfer；打印机未插时，SDK 内部会在 USB endpoint
     * 上阻塞到 transfer timeout —— 这个超时由 Android USB 栈决定，我们不能缩短它，
     * 只能在它之上叠加我们自己的 5s 兜底，<b>让用户点了按钮之后立即看到"打印机不可用"，
     * 而不是傻等 20s 才超时</b>。
     */
    private static final long PRINT_STATUS_TIMEOUT_SECONDS = 5L;

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
        // printExecutor 是 printPhoto 专用，shutdown 时一并停掉。
        // 注意：卡死兜底时 future.cancel(true) 不一定能打断 native，
        // shutdownNow() 会给 doService 线程发一次 interrupt，至少能清理队列。
        printExecutor.shutdownNow();
    }

    // ---- SDK ops: each returns Result via callback on the main thread ----

    public void getPrinterStatus(Callback<Object> cb) {
        // 状态探测：5s 短超时，让"打印机不可用"快速暴露
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
     * HiTi 打印入口。行为对齐 SDK 自带 SampleAPK 的 MainActivity b_printPhoto：
     * <ol>
     *   <li>每次调用 {@code new Thread(...).start()} 起一个全新的 raw Thread，
     *       完全脱离本类原有的 {@code io} 单线程 executor，doService(USB_PRINT_PHOTOS)
     *       不会被前一次任务串行卡住。</li>
     *   <li>{"set m_strTablesRoot → doService → reset m_strTablesRoot"} 同步成对执行，
     *       与 sample MainActivity 行 651-655 完全一致。</li>
     *   <li>doService 走 {@link #doServiceWithTimeout} 统一兜底：超时由 native 端
     *       强制 cancel Future，错误信息里带 action+jobId 便于调试。TS 端还有
     *       一层 Promise.race 30s 看门狗，双保险。</li>
     *   <li>MATTE/PRINTCOUNT/PRINTMODE/PaperType 默认值与 sample MainActivity 一致：
     *       MATTE=1（覆膜）、PRINTCOUNT=1、PRINTMODE=0、PaperType=2（4x6）。</li>
     *   <li>返回值：成功时回调 {@code data} 是 native {@code retrieveSampleData}
     *       拼出来的字符串 {@code "<<<USB_PRINT_PHOTOS -ID<id> : err <0x... desc>"}；
     *       失败时 {@code error} 同样被填上这段字符串以便前端展示。</li>
     * </ol>
     */
    public void printPhoto(final SamplePrintOptions opts, final Callback<Object> cb) {
        logD("printPhoto() called: " + opts);

        if (opts == null || opts.bitmapPath == null || opts.bitmapPath.isEmpty()) {
            logE("printPhoto: bitmapPath is empty");
            post(cb, null, "bitmapPath is required");
            return;
        }

        // 严格按 sample: 不复用 io executor；每次都 raw new Thread
        Thread sampleThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 1) 等 Tables assets 解压完成
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

                    // 2) 拷贝字段到本地 final
                    final int PRINTCOUNT = opts.printCount;
                    final short MATTE     = opts.matte;
                    final short PRINTMODE = opts.printMode;
                    final int PaperType   = opts.paperType;
                    logD("printPhoto: sample defaults PRINTCOUNT=" + PRINTCOUNT
                            + " MATTE=" + MATTE + " PRINTMODE=" + PRINTMODE
                            + " PaperType=" + PaperType);

                    // 3) 创建 PrinterJob，并把 SDK 期望的 bitmap attr 装配进去
                    int jobId = nextJobId++;
                    final PrinterJob job = new PrinterJob(jobId, Action.USB_PRINT_PHOTOS);
                    logD("printPhoto: PrinterJob created id=" + jobId);

                    Object attr = buildPhotoAttrSample(opts.bitmapPath, PaperType,
                            PRINTCOUNT, MATTE, PRINTMODE, tablesRoot);
                    if (attr == null) {
                        logE("printPhoto: buildPhotoAttrSample returned null");
                        post(cb, "USB_PRINT_PHOTOS -ID" + jobId
                                + " : err <0x? Failed to decode bitmap from " + opts.bitmapPath + ">", null);
                        return;
                    }
                    job.setJobPara(attr);

                    // 4) 严格按 sample MainActivity 行 651-655 顺序：
                    //    先 serviceConnector.m_strTablesRoot = tablesRoot；
                    //    再 serviceConnector.doService(job)；
                    //    doService 返回后立即把 m_strTablesRoot 置空。
                    if (serviceConnector == null) {
                        logE("printPhoto: serviceConnector is null");
                        post(cb, "USB_PRINT_PHOTOS -ID" + jobId
                                + " : err <0x? ServiceConnector not initialized>", null);
                        return;
                    }

                    logD("printPhoto: pre-set serviceConnector.m_strTablesRoot='" + tablesRoot + "'");
                    serviceConnector.m_strTablesRoot = tablesRoot;

                    // 严格按 sample MainActivity#operatePrinter(USB_PRINT_PHOTOS) 行 651-655：
                    //   raw Thread 里同步 set m_strTablesRoot → 同步 doService → 同步 reset。
                    // a3fd715 引入 printExecutor.submit(() -> doService) 会把 doService 从 raw Thread
                    // 移到独立线程，HiTi SDK 的 doService 在 rockchip/SDK 34 上偶发卡死 20s。
                    // 实测在 raw Thread 里直接同步调用更接近 sample APK 行为，能正常返回。
                    logD("printPhoto: calling serviceConnector.doService synchronously on " + Thread.currentThread().getName());
                    long doServiceStartMs = System.currentTimeMillis();
                    try {
                        serviceConnector.doService(job);
                    } finally {
                        // 不管 doService 成功还是抛错，都把 m_strTablesRoot 立刻置空（sample 行为）。
                        String before = serviceConnector.m_strTablesRoot;
                        serviceConnector.m_strTablesRoot = "";
                        long doServiceMs = System.currentTimeMillis() - doServiceStartMs;
                        logD("printPhoto: doService returned on " + Thread.currentThread().getName()
                                + " after " + doServiceMs + "ms; m_strTablesRoot was='" + before + "'");
                    }

                    String errCodeStr;
                    if (job.errCode == null) {
                        errCodeStr = "0x? null";
                    } else {
                        errCodeStr = "0x" + Integer.toHexString(job.errCode.value)
                                + " " + String.valueOf(job.errCode.description);
                    }
                    logD("printPhoto: post-doService errCode=" + errCodeStr
                            + " retData=" + job.retData);

                    // 5) 走 sample retrieveData 字符串化输出
                    String sampleOutput = retrieveSampleData(job);
                    logD("printPhoto: retrieveSampleData -> " + sampleOutput.replace('\n', '\\'));

                    // 喂一个 SDK-style 行到 native 日志文件以便后续检索
                    if (printLogger != null) {
                        printLogger.java(TAG, "[SAMPLE_OUTPUT] " + sampleOutput.replace('\n', ' '));
                    }

                    // 传给前端：把 errCode 归 0 当作"成功"，其它都算失败
                    boolean errOk = job.errCode != null && job.errCode.value == 0;
                    if (errOk) {
                        post(cb, sampleOutput, null);
                    } else {
                        post(cb, sampleOutput, sampleOutput);
                    }
                } catch (Throwable t) {
                    logE("printPhoto raw Thread failed", t);
                    post(cb, null, t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
                }
            }
        }, "HiTi-PrintPhoto-" + System.currentTimeMillis());
        sampleThread.start();
        logD("printPhoto: raw Thread started, name=" + sampleThread.getName());
    }

    /**
     * sample {@code retrieveData} 同款字符串化输出。
     * 行为差异：sample 把它当 UI TextView 里追加的字符串，我们把它当结果回传给前端。
     *
     * 输出格式：
     * {@code "\n\n<<<USB_PRINT_PHOTOS -ID<id> : err <0x<hex> <desc>>\n..."}
     */
    private String retrieveSampleData(PrinterJob job) {
        if (job == null) return "\n\n<<<null job>>>";
        StringBuilder bu = new StringBuilder("\n\n<<<");
        bu.append(job.action.name()).append(" -ID").append(job.getId())
                .append(" : err <0x");
        if (job.errCode == null) {
            bu.append("? null>");
        } else {
            bu.append(Integer.toHexString(job.errCode.value)).append(" ")
                    .append(String.valueOf(job.errCode.description)).append(">");
        }
        if (job.retData == null) return bu.toString();
        // sample 的 switch 里 USB_PRINT_PHOTOS 不展开 retData，这里保留一致行为：
        // 不再追加额外内容，与 sample retrieveData 行 837 (USB_PRINT_PHOTOS 的 break) 对齐。
        return bu.toString();
    }

    /**
     * SampleAPK 风格的 getPrintPhotoPara 包装：
     * 解码 bitmap → 按 sample 的 PaperSize switch 选 Size → 装配 PRINTCOUNT/MATTE/PRINTMODE。
     * 全部参数开放（PRINTCOUNT/MATTE/PRINTMODE），与 sample MainActivity
     * {@code operatePrinter(... USB_PRINT_PHOTOS)} 设置实例字段后再调用的同款语义。
     */
    private Object buildPhotoAttrSample(String bitmapPath, int paperType,
                                        int printCount, short matte, short printMode,
                                        String tablesRootForPara) {
        logD("buildPhotoAttrSample: bitmapPath=" + bitmapPath + " paperType=" + paperType
                + " printCount=" + printCount + " matte=" + matte + " printMode=" + printMode);
        android.graphics.Bitmap rawBitmap = android.graphics.BitmapFactory.decodeFile(bitmapPath);
        if (rawBitmap == null) {
            logE("buildPhotoAttrSample: BitmapFactory.decodeFile returned null");
            return null;
        }
        logD("buildPhotoAttrSample: decoded bitmap " + rawBitmap.getWidth() + "x" + rawBitmap.getHeight());

        PaperSize size;
        switch (paperType) {
            case 3: size = PaperSize.PAPER_SIZE_5X7_PHOTO; break;
            case 4: size = PaperSize.PAPER_SIZE_6X8_PHOTO; break;
            case 5: size = PaperSize.PAPER_SIZE_6X4_SPLIT_2UP; break;
            case 6: size = PaperSize.PAPER_SIZE_6X6_PHOTO; break;
            case 2:
            default: size = PaperSize.PAPER_SIZE_6X4_PHOTO; break;
        }
        logD("buildPhotoAttrSample: PaperSize=" + size);

        // 竖图旋转：HiTi 6×4 paper 是 landscape（宽 > 高）。竖图直接进 SDK 会 letterbox
        // 左右留白，打印出来感觉"方向不对"。这里检测 portrait bitmap 并旋转 90° 顺时针，
        // 让主体填满 landscape paper。rotation 只做一次，normalize 接着按 landscape 比例裁切。
        android.graphics.Bitmap bitmap = rawBitmap;
        if (rawBitmap.getWidth() < rawBitmap.getHeight()) {
            logD("buildPhotoAttrSample: portrait bitmap detected ("
                    + rawBitmap.getWidth() + "x" + rawBitmap.getHeight()
                    + "), rotating 90° clockwise for landscape paper");
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.postRotate(90f);
            android.graphics.Bitmap rotated = android.graphics.Bitmap.createBitmap(
                    rawBitmap, 0, 0, rawBitmap.getWidth(), rawBitmap.getHeight(),
                    matrix, true);
            bitmap = rotated;
            logD("buildPhotoAttrSample: rotated bitmap " + bitmap.getWidth() + "x" + bitmap.getHeight());
        }

        // 归一化到 PaperSize 比例：按 landscape 比例 center-crop。
        // fitImageToPaper 已输出一致的 1536×1024 landscape，这里只处理偶发的
        // PNG dataURL / image.decode() 回退 / Capacitor 内嵌图等异常路径。
        bitmap = normalizeBitmapToPaperSize(bitmap, size);
        if (bitmap != rawBitmap) {
            logD("buildPhotoAttrSample: normalized to " + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + " (was " + rawBitmap.getWidth() + "x" + rawBitmap.getHeight() + ")");
        }

        Object para = PrintPara.getPrintPhotoPara(bitmap,
                (short) printCount, matte, printMode, size, tablesRootForPara);
        logD("buildPhotoAttrSample: PrintPara.getPrintPhotoPara returned "
                + (para == null ? "null" : para.getClass().getSimpleName()));
        return para;
    }

    /**
     * 强制把 bitmap 归一化到 PaperSize 期望的宽高比（宽比高）。
     *
     * <p>不同 PaperSize 的目标比例（来自 HiTi SDK 注释）：
     * <ul>
     *   <li>6×4 / 6×4 SPLIT_2UP → 1.487 (1844/1240 ≈ 1240/1844 取决于方向，按 landscape 算)</li>
     *   <li>5×7 → 1548/2140 ≈ 0.723 (portrait)</li>
     *   <li>6×8 → 1844/2434 ≈ 0.758 (portrait)</li>
     *   <li>6×6 → 1.0 (square)</li>
     * </ul>
     *
     * <p>实测在 6×4 landscape paper 上，SDK 收到 portrait bitmap 会 letterbox
     * 上下留白（不 rotate）。所以这里做 cover-fit center-crop：保留主体方向，
     * 按 PaperSize 比例切掉多余部分（左右或上下），让 bitmap 比例严格等于
     * paper 比例。
     */
    private android.graphics.Bitmap normalizeBitmapToPaperSize(
            android.graphics.Bitmap src, PaperSize size) {
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        if (srcW <= 0 || srcH <= 0) return src;

        // 目标比例：宽 / 高（landscape > 1；portrait < 1；square = 1）
        float targetRatio;
        switch (size) {
            case PAPER_SIZE_6X4_PHOTO:        targetRatio = 1844f / 1240f; break; // ~1.487
            case PAPER_SIZE_6X4_SPLIT_2UP:    targetRatio = 1240f / 1844f; break; // ~0.672 (portrait split)
            case PAPER_SIZE_5X7_PHOTO:        targetRatio = 1548f / 2140f; break; // ~0.723
            case PAPER_SIZE_5X7_SPLIT_2UP:    targetRatio = 2152f / 1548f; break; // ~1.390
            case PAPER_SIZE_6X8_PHOTO:        targetRatio = 1844f / 2434f; break; // ~0.758
            case PAPER_SIZE_6X9_PHOTO:        targetRatio = 1844f / 2740f; break; // ~0.673
            case PAPER_SIZE_6X9_SPLIT_2UP:    targetRatio = 2492f / 1844f; break; // ~1.351
            case PAPER_SIZE_6X6_PHOTO:        targetRatio = 1.0f; break;
            default:                          targetRatio = 1844f / 1240f;
        }

        float srcRatio = (float) srcW / (float) srcH;
        if (Math.abs(srcRatio - targetRatio) < 0.01f) {
            return src; // 比例已经一致
        }

        int outW, outH;
        if (srcRatio > targetRatio) {
            // src 比 target 更宽，按高度对齐（左右各裁一些）
            outH = srcH;
            outW = Math.round(srcH * targetRatio);
        } else {
            // src 比 target 更高，按宽度对齐（上下各裁一些）
            outW = srcW;
            outH = Math.round(srcW / targetRatio);
        }
        int sx = (srcW - outW) / 2;
        int sy = (srcH - outH) / 2;
        android.graphics.Bitmap cropped = android.graphics.Bitmap.createBitmap(
                src, sx, sy, outW, outH);
        logD("normalizeBitmapToPaperSize: center-cropped " + srcW + "x" + srcH
                + " → " + outW + "x" + outH
                + " (srcRatio=" + srcRatio + " targetRatio=" + targetRatio + ")");
        return cropped;
    }

    /** 与 sample PrinterOperation 字段语义对齐的参数包。 */
    public static class SamplePrintOptions {
        /** 已写到磁盘的 JPEG/bitmap 绝对路径。sample 语义下就是 drawable 资源落地后的路径。 */
        public final String bitmapPath;
        /** PaperType: 2=4x6, 3=5x7, 4=6x8, 5=4x6 split 2up, 6=6x6。与 sample MainActivity 一致。 */
        public final int paperType;
        /** PRINTCOUNT: 想要打印的份数。sample 默认 1。 */
        public final int printCount;
        /** MATTE: 1=matte(覆膜), 0=not matte。sample 默认 1。 */
        public final short matte;
        /** PRINTMODE: 仅 P232W 有效，0=standard, 1=fine(HOD)。sample 默认 0。 */
        public final short printMode;

        public SamplePrintOptions(String bitmapPath, int paperType,
                                  int printCount, short matte, short printMode) {
            this.bitmapPath = bitmapPath;
            this.paperType = paperType;
            this.printCount = printCount;
            this.matte = matte;
            this.printMode = printMode;
        }

        @Override
        public String toString() {
            return "SamplePrintOptions{bitmapPath='" + bitmapPath + "' paperType=" + paperType
                    + " printCount=" + printCount + " matte=" + matte + " printMode=" + printMode
                    + "}";
        }
    }

    public void startService(Callback<ErrorCode> cb) {
        logD("startService() called, serviceConnector=" + serviceConnector);
        mainHandler.post(() -> {
            if (serviceConnector == null) {
                logE("startService: ServiceConnector is null (was releaseForPage called on page exit?)");
                post(cb, null, "ServiceConnector not initialized — call initForPage first");
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
        runOp(action, data, format, PRINT_PHOTO_TIMEOUT_SECONDS, cb);
    }

    /**
     * runOp 的可指定超时版本。{@link #runOp(Action, Object, byte, Callback)}
     * 默认走 20s 打印超时；状态探测类调用（{@link #getPrinterStatus} 等）传
     * {@link #PRINT_STATUS_TIMEOUT_SECONDS} = 5s，让"打印机不可用"快速暴露。
     */
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
                logD("runOp: jobId=" + jobId + " action=" + action);
                if (data != null) {
                    Object para = buildSetPara(action, data);
                    if (para != null) job.setJobPara(para);
                }
                serviceConnector.m_strTablesRoot = tablesRoot;

                // 统一兜底：把 doService 提交到 printExecutor 拿 Future，外层用 get(timeout) 等；
                // SDK 卡死不返回则回调超时错误，让 TS 端 await 不再无限挂起。
                doServiceWithTimeout(job, timeoutSeconds, new Callback<String>() {
                    @Override public void onSuccess(String errStr) {
                        String errCodeStr = job.errCode == null ? "null" :
                            "0x" + Integer.toHexString(job.errCode.value) + " desc=" + String.valueOf(job.errCode.description);
                        logD("runOp: doService(" + action + ", jobId=" + jobId + ") returned, errCode=" + errCodeStr + " retData=" + job.retData);
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

    /**
     * 统一的 doService 兜底包装：所有走 {@code serviceConnector.doService(job)} 的调用
     * 都通过这里，避免 SDK 卡死不返回时 TS 端 await 永远不 resolve。
     *
     * <p>关键设计：
     * <ul>
     *   <li>用 {@link #printExecutor}（专用单线程 executor）跑 doService，
     *       跟 {@link #io} 严格隔离，不会自死锁（详见字段 javadoc）。</li>
     *   <li>{@link Future#get(long, TimeUnit)} 同步等待，timeout 后 callback
     *       拿到 error，错误信息里带 action + jobId，调试时能立刻分辨是哪个
     *       USB 操作卡住，而不是把超时和业务错误混在一起。</li>
     *   <li>回调已经在调用方线程（{@code io} 线程 / raw Thread）里发生，调用方
     *       只需把业务结果（payload / errStr）通过 {@link #post} 投递回主线程。
     *       doServiceWithTimeout 本身不再 post，避免双重 post。</li>
     *   <li>timeout 秒数由调用方传入：打印用 {@link #PRINT_PHOTO_TIMEOUT_SECONDS}，
     *       状态探测用更短的 {@link #PRINT_STATUS_TIMEOUT_SECONDS}，
     *       让"打印机不可用"在用户点击后能立即暴露。</li>
     * </ul>
     */
    private void doServiceWithTimeout(final PrinterJob job, final Callback<String> cb) {
        doServiceWithTimeout(job, PRINT_PHOTO_TIMEOUT_SECONDS, cb);
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
            logE("doServiceWithTimeout: " + opName + " timed out after "
                    + timeoutSeconds + "s, errCode=" + errCodeStr
                    + " retData=" + job.retData + " — forcing failure", te);
            boolean cancelled = future.cancel(true);
            logE("doServiceWithTimeout: future.cancel(true) returned " + cancelled
                    + " (doService may still be running on HiTiPrinterPrint thread)");
            cb.onError("HiTi " + job.action + " timed out after "
                    + timeoutSeconds + "s (doService did not return; SDK possibly stuck, jobId="
                    + job.getId() + ")");
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            logE("doServiceWithTimeout: " + opName + " threw", cause);
            cb.onError(cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            logE("doServiceWithTimeout: interrupted while waiting for " + opName, ie);
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