package com.children.doctor.plugins.dualcamera;

import android.content.Context;
import android.content.Intent;
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
 * 拍摄会话日志器。
 *
 * <p>每次"打开 app → 关闭 app / 主动结束会话"为一个日志文件，文件路径：
 * <pre>getExternalFilesDir(null)/capture_logs/capture_YYYYMMDD_HHmmss_SSS.log</pre>
 *
 * <p>特点：
 * <ul>
 *   <li>独立 HandlerThread，所有 IO 不阻塞主线程 / Camera2 线程</li>
 *   <li>线程安全：所有写操作 post 到 HandlerThread 串行化</li>
 *   <li>同一进程可多次 startSession/closeSession（覆盖式文件名递增）</li>
 *   <li>文件路径通过 FileProvider 暴露给前端，AndroidManifest 已配 *.fileprovider 通用规则</li>
 * </ul>
 */
public class CaptureLogger {

    private static final String TAG = "CaptureLogger";
    private static final String LOG_DIR_NAME = "capture_logs";
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024; // 2 MB

    private static volatile CaptureLogger instance;

    private final Context appContext;
    private final HandlerThread ioThread;
    private final Handler ioHandler;

    private volatile File currentLogFile;
    private volatile long currentBytes;
    private volatile boolean sessionOpen = false;
    private final Object lock = new Object();

    private CaptureLogger(Context context) {
        this.appContext = context.getApplicationContext();
        this.ioThread = new HandlerThread("CaptureLogger-IO");
        this.ioThread.start();
        this.ioHandler = new Handler(ioThread.getLooper());
    }

    public static CaptureLogger get(Context context) {
        if (instance == null) {
            synchronized (CaptureLogger.class) {
                if (instance == null) {
                    instance = new CaptureLogger(context);
                }
            }
        }
        return instance;
    }

    /**
     * 启动一次新的拍摄会话日志。会清空 currentLogFile 引用并创建新文件。
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
            currentLogFile = new File(dir, "capture_" + stamp + ".log");
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
        writeLine("Capture session started");
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
     * @param tag   模块名（Camera2Session、Camera2Controller、JS 等）
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
                writeLine("Capture session ended");
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
     * 返回当前日志文件大小（字节）。前端可用来显示"已写 1.2 MB"。
     */
    public long getCurrentLogSize() {
        return currentBytes;
    }

    /**
     * 通过 FileProvider 暴露当前日志文件的 content:// URI。
     * 用于前端调用 Capacitor Share API 时把日志发出去。
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
                "capture_logs_export_" + humanName + ".log");
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

    private void writeLine(String line) {
        File f = currentLogFile;
        if (f == null) return;
        byte[] data = (line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (FileOutputStream fos = new FileOutputStream(f, true)) {
            fos.write(data);
            currentBytes += data.length;
            if (currentBytes > MAX_FILE_BYTES) {
                fos.write(("\n[CaptureLogger] Log file exceeded "
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