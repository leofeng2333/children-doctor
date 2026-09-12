package com.children.doctor.plugins.dualcamera;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * 把 {@link CaptureConfig} 持久化到 {@code <filesDir>/capture_config.json}。
 *
 * <p>读取策略（{@link #loadOrCreate}）：
 * <ol>
 *   <li>文件不存在 → 返回 {@link CaptureConfig#defaults()} 并立即写回磁盘</li>
 *   <li>文件存在但解析失败 → 记 WARN 日志，重置为默认值并写回</li>
 *   <li>文件存在且合法 → 反序列化返回</li>
 * </ol>
 *
 * <p>写入采用覆盖写（FileWriter truncate=true）。写入失败仅记日志，不抛异常，
 * 避免配置 I/O 把启动流程拖崩。
 */
public final class CaptureConfigRepository {

    private static final String TAG = "CaptureConfigRepo";
    private static final String FILE_NAME = "capture_config.json";

    private final File configFile;

    public CaptureConfigRepository(Context context) {
        this.configFile = new File(context.getFilesDir(), FILE_NAME);
    }

    /** 测试 / 调试用：可指定任意路径。 */
    public CaptureConfigRepository(File configFile) {
        this.configFile = configFile;
    }

    /**
     * 读取配置；不存在或解析失败时自动用默认值重建一份。
     *
     * <p>任何 I/O / 解析异常都被吞掉，最坏情况是退回到默认值，不会让相机启动失败。
     */
    public CaptureConfig loadOrCreate() {
        if (!configFile.exists()) {
            CaptureConfig defaults = CaptureConfig.defaults();
            Log.i(TAG, "capture config not found at " + configFile.getAbsolutePath()
                    + ", creating with defaults: " + defaults.debugSummary());
            save(defaults);
            return defaults;
        }

        String raw = readAll(configFile);
        if (raw == null) {
            // 读取阶段 I/O 失败：备份坏文件，重置为默认
            Log.w(TAG, "failed to read capture config at " + configFile.getAbsolutePath()
                    + ", recreating with defaults");
            backupCorruptedAndReset();
            CaptureConfig defaults = CaptureConfig.defaults();
            save(defaults);
            return defaults;
        }

        try {
            CaptureConfig cfg = CaptureConfig.fromJson(raw);
            Log.i(TAG, "loaded capture config: " + cfg.debugSummary());
            return cfg;
        } catch (JSONException e) {
            Log.w(TAG, "capture config JSON parse failed, recreating: " + e.getMessage());
            backupCorruptedAndReset();
            CaptureConfig defaults = CaptureConfig.defaults();
            save(defaults);
            return defaults;
        }
    }

    /**
     * 把配置写回磁盘。失败仅记日志。
     */
    public void save(CaptureConfig config) {
        try (FileWriter w = new FileWriter(configFile, false)) {
            w.write(config.toJson().toString(2));
        } catch (IOException | JSONException e) {
            Log.e(TAG, "failed to save capture config to " + configFile.getAbsolutePath(), e);
        }
    }

    public File getConfigFile() {
        return configFile;
    }

    private String readAll(File f) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (IOException e) {
            Log.w(TAG, "readAll failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 把损坏文件改名为 {@code .corrupt-<timestamp>} 保留证据，然后下次 loadOrCreate
     * 会自动重建一份新的。
     */
    private void backupCorruptedAndReset() {
        try {
            File backup = new File(configFile.getAbsolutePath()
                    + ".corrupt-" + System.currentTimeMillis());
            if (configFile.renameTo(backup)) {
                Log.w(TAG, "moved corrupted config to " + backup.getAbsolutePath());
            }
        } catch (Exception e) {
            // 备份失败也不致命，下次 loadOrCreate 会再次覆盖写
            Log.w(TAG, "backupCorrupted failed: " + e.getMessage());
        }
    }
}
