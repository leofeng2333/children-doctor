package com.children.doctor.plugins.dualcamera;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 把 {@link CaptureConfig} 持久化到 <b>外部 app-specific 目录</b>下的
 * {@code capture_config.json}。
 *
 * <p><b>为什么走外部存储而不是 {@link Context#getFilesDir()}：</b>
 * 现场校准流程需要"设备 A 调好 → 拷到设备 B 同路径 → 立即可用"。
 * 内部存储 {@code /data/data/<pkg>/files/} 是 app-private，跨设备复制需要 root；
 * 外部 app-specific 目录 {@code /storage/emulated/0/Android/data/<pkg>/files/}
 * 仍然是 app-private（其他 app 读不到），但 adb / MTP / 文件管理器都能直接访问，
 * 且在相同包名设备上<b>路径完全一致</b>——这是"拷贝即生效"的唯一前提。
 *
 * <p>读取策略（{@link #loadOrCreate}）：
 * <ol>
 *   <li>外部文件不存在 → 探测内部是否有遗留老文件 → 有则自动迁移</li>
 *   <li>都没有 → 返回 {@link CaptureConfig#defaults()} 并立即写回磁盘</li>
 *   <li>文件存在但解析失败 → 记 WARN 日志，把损坏文件改名为 {@code .corrupt-<ts>}
 *       留证，重置为默认值并写回</li>
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
        this.configFile = resolveConfigFile(context);
    }

    /** 测试 / 调试用：可指定任意路径。 */
    public CaptureConfigRepository(File configFile) {
        this.configFile = configFile;
    }

    /**
     * 解析最终的落盘路径：
     * <ol>
     *   <li>优先 {@link Context#getExternalFilesDir(String) getExternalFilesDir(null)}
     *       （adb / MTP 可直达，跨设备同名包路径一致）</li>
     *   <li>外部目录可用，但新路径没有文件而内部老路径有 → 一次性迁移到外部，然后删掉老文件</li>
     *   <li>外部目录不可用（null，通常是存储未挂载）→ 退回到内部路径</li>
     * </ol>
     */
    private static File resolveConfigFile(Context context) {
        File legacy = new File(context.getFilesDir(), FILE_NAME);
        File externalDir = context.getExternalFilesDir(null);
        if (externalDir != null) {
            if (!externalDir.exists() && !externalDir.mkdirs()) {
                Log.w(TAG, "externalFilesDir mkdirs failed at " + externalDir.getAbsolutePath());
            }
            File target = new File(externalDir, FILE_NAME);
            if (!target.exists() && legacy.exists()) {
                if (copyFile(legacy, target)) {
                    Log.i(TAG, "migrated legacy capture config from internal to external: "
                            + target.getAbsolutePath());
                    if (!legacy.delete()) {
                        Log.w(TAG, "failed to delete legacy file at " + legacy.getAbsolutePath());
                    }
                } else {
                    Log.w(TAG, "migration copy failed; using external path anyway: "
                            + target.getAbsolutePath());
                }
            }
            return target;
        }
        Log.w(TAG, "externalFilesDir unavailable, falling back to internal: "
                + legacy.getAbsolutePath());
        return legacy;
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

    /**
     * 当前生效的配置文件路径。开发者工具弹窗会把这个路径展示给用户，
     * 方便现场调好后拷贝到其他设备同路径下。
     */
    public File getConfigFile() {
        return configFile;
    }

    /**
     * 把 {@code src} 的字节流完整复制到 {@code dst}（覆盖写）。失败返回 false。
     */
    private static boolean copyFile(File src, File dst) {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return true;
        } catch (IOException e) {
            Log.w(TAG, "copyFile " + src + " -> " + dst + " failed: " + e.getMessage());
            return false;
        }
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
