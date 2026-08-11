package com.children.doctor.plugins.hitiprinter;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 打印会话日志器。
 *
 * <p>每次"开始打印任务 → 打印完成 / 失败"为一个日志文件，文件路径：
 * <pre>getExternalFilesDir(null)/print_logs/print_YYYYMMDD_HHmmss_SSS.log</pre>
 *
 * <p>设计参考 {@code CaptureLogger}：
 * <ul>
 *   <li>独立 HandlerThread，所有 IO 不阻塞主线程 / USB 线程</li>
 *   <li>线程安全：所有写操作 post 到 HandlerThread 串行化</li>
 *   <li>同一进程可多次 startSession/closeSession（覆盖式文件名递增）</li>
 *   <li>文件路径通过 FileProvider 暴露给前端</li>
 * </ul>
 *
 * <p>与 CaptureLogger 的关键差异：
 * <ul>
 *   <li>实例不共享（plugin load() 中独立 new）；避免与拍摄日志混在一起</li>
 *   <li>目录名 / 文件名前缀改为 print_*</li>
 * </ul>
 */
public class PrintLogger {

    private static final String TAG = "PrintLogger";
    private static final String LOG_DIR_NAME = "print_logs";
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024; // 2 MB

    private final Context appContext;
    private final HandlerThread ioThread;
    private final Handler ioHandler;

    private volatile File currentLogFile;
    private volatile long currentBytes;
    private volatile boolean sessionOpen = false;
    private final Object lock = new Object();

    public PrintLogger(Context context) {
        this.appContext = context.getApplicationContext();
        this.ioThread = new HandlerThread("PrintLogger-IO");
        this.ioThread.start();
        this.ioHandler = new Handler(ioThread.getLooper());
    }

    /**
     * 启动一次新的打印会话日志。会清空 currentLogFile 引用并创建新文件。
     * @return 日志文件绝对路径；如果 IO 失败返回 null
     */
    public String startSession() {
        synchronized (lock) {
            sessionOpen = true;
            currentBytes = 0;
            File dir = new File(appContext.getExternalFilesDir(null), LOG_DIR_NAME);
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Cannot create log dir: " + dir);
                return null;
            }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                    .format(new Date());
            currentLogFile = new File(dir, "print_" + stamp + ".log");
            try {
                if (!currentLogFile.createNewFile()) {
                    Log.w(TAG, "Log file already exists: " + currentLogFile);
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to create log file", e);
                return null;
            }
            writeHeader();
            return currentLogFile.getAbsolutePath();
        }
    }

    private void writeHeader() {
        String ts = isoTimestamp();
        String device = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " (SDK " + android.os.Build.VERSION.SDK_INT + ")";
        writeLine("================================================================");
        writeLine("Print session started");
        writeLine("  start_time:    " + ts);
        writeLine("  device:        " + device);
        writeLine("  app_version:   " + readAppVersion());
        writeLine("================================================================");
        writeLine("");
    }

    private String readAppVersion() {
        try {
            android.content.pm.PackageInfo pi = appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0);
            return pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 写一行日志。线程安全，内部 post 到 IO 线程。
     * @param layer JS / JAVA / NATIVE
     * @param tag   模块名（HiTiPrinterManager、HiTiPrinterPlugin、JS 等）
     * @param msg   日志内容（不允许包含换行；调用方需要时自行换行多次调用）
     */
    public void log(String layer, String tag, String msg) {
        // 防御性：去掉 msg 里的换行避免破坏单行结构
        if (msg == null) msg = "<null>";
        msg = msg.replace('\n', ' ').replace('\r', ' ');
        String line = isoTimestamp() + " [" + layer + "][" + tag + "] " + msg;
        // 后台线程 post 写入；不要做同步 flush（避免阻塞调用方）
        ioHandler.post(() -> writeLine(line));
    }

    /**
     * 便捷重载：写 Java 层的日志（layer = "JAVA"）
     */
    public void java(String tag, String msg) {
        log("JAVA", tag, msg);
    }

    /**
     * 关闭当前会话（写入 footer）。文件保留在磁盘上。
     */
    public void closeSession() {
        synchronized (lock) {
            if (!sessionOpen) return;
            sessionOpen = false;
            ioHandler.post(() -> {
                writeLine("");
                writeLine("================================================================");
                writeLine("Print session ended");
                writeLine("  end_time: " + isoTimestamp());
                writeLine("  total_bytes: " + currentBytes);
                writeLine("================================================================");
            });
        }
    }

    /**
     * 当前会话文件路径，可能为 null（未启动）。
     */
    public String getCurrentLogFilePath() {
        File f = currentLogFile;
        return f != null ? f.getAbsolutePath() : null;
    }

    /**
     * 返回当前日志文件大小（字节）。
     */
    public long getCurrentLogSize() {
        return currentBytes;
    }

    /**
     * 通过 FileProvider 暴露当前日志文件的 content:// URI。
     */
    public Uri getCurrentLogUri() {
        File f = currentLogFile;
        if (f == null) return null;
        return FileProvider.getUriForFile(
                appContext,
                appContext.getPackageName() + ".fileprovider",
                f);
    }

    /**
     * 把日志文件写入到一个公共下载目录，返回 file:// URI（仅用于 adb pull / Files app 可见）。
     */
    public String exportToDownloads(String humanName) {
        File src = currentLogFile;
        if (src == null) return null;
        File out = new File(appContext.getExternalFilesDir(null),
                "print_logs_export_" + humanName + ".log");
        try (FileOutputStream fos = new FileOutputStream(out);
             java.io.FileInputStream fis = new java.io.FileInputStream(src)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
            return out.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "exportToDownloads failed", e);
            return null;
        }
    }

    /**
     * 关闭后台线程。应当在 plugin destroy 时调用。
     */
    public void shutdown() {
        if (ioThread != null) {
            ioThread.quitSafely();
        }
    }

    private void writeLine(String line) {
        File f = currentLogFile;
        if (f == null) return;
        byte[] data = (line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (FileOutputStream fos = new FileOutputStream(f, true)) {
            fos.write(data);
            currentBytes += data.length;
            if (currentBytes > MAX_FILE_BYTES) {
                fos.write(("\n[PrintLogger] Log file exceeded "
                        + MAX_FILE_BYTES + " bytes, truncating.\n").getBytes());
                // 简单策略：直接截断——本会话后续不再写新行
                currentBytes = MAX_FILE_BYTES + 1;
                currentLogFile = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "writeLine failed: " + e.getMessage());
        }
    }

    private static String isoTimestamp() {
        SimpleDateFormat fmt = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
        fmt.setTimeZone(TimeZone.getDefault());
        return fmt.format(new Date());
    }
}