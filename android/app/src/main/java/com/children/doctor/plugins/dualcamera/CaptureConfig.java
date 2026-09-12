package com.children.doctor.plugins.dualcamera;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * 拍照方向校准配置。现场可根据左右 UVC 摄像头实测角度修改，简化调参流程。
 *
 * <p>由 {@link CaptureConfigRepository} 负责持久化到
 * {@code <filesDir>/capture_config.json}。读不到文件时使用 {@link #defaults()} 并写回磁盘。
 *
 * <p>字段含义：
 * <ul>
 *   <li>{@code slots[i].previewRotation} → {@code Camera2Session.setCalibration(rotate, mirror)}
 *       的额外旋转角（0/90/180/270）</li>
 *   <li>{@code slots[i].captureRotation} → {@code Camera2Session.setCaptureRotationOffset(rotate)}
 *       的额外旋转角（0/90/180/270）</li>
 *   <li>{@code slots[i].mirror} → {@code setCalibration} 的第二个参数，是否再补一次水平镜像</li>
 * </ul>
 *
 * <p>索引约定：{@code slots[0]} = 左预览，{@code slots[1]} = 右预览（与
 * {@code Camera2Controller.startPreview} 的 for 循环 i 严格对齐）。
 */
public final class CaptureConfig {

    public static final int CURRENT_VERSION = 1;

    /** 配置生效的 slot 数量。当前项目硬编码为左右两个 UVC。 */
    public static final int SLOT_COUNT = 2;

    public static final class Slot {
        public int previewRotation;
        public int captureRotation;
        public boolean mirror;
        /**
         * 摄像头缩放倍数（1.0 = 原画，>1.0 = 数字放大）。
         *
         * <p>Camera2 上 preview 和 capture 共享同一个 {@code SCALER_CROP_REGION}，所以这里
         * 只有一个值，preview / capture 同时生效。
         */
        public float zoom;

        public Slot() {
            this.previewRotation = 0;
            this.captureRotation = 0;
            this.mirror = false;
            this.zoom = 1.0f;
        }

        public Slot(int previewRotation, int captureRotation, boolean mirror, float zoom) {
            this.previewRotation = previewRotation;
            this.captureRotation = captureRotation;
            this.mirror = mirror;
            this.zoom = zoom;
        }

        public JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("previewRotation", previewRotation)
                    .put("captureRotation", captureRotation)
                    .put("mirror", mirror)
                    .put("zoom", zoom);
        }

        public static Slot fromJson(JSONObject o) throws JSONException {
            // 容错：缺字段时退回到默认值，避免老配置文件字段缺失时整盘挂掉
            float zoom = (float) o.optDouble("zoom", 1.0);
            if (!Float.isFinite(zoom) || zoom < 1.0f) zoom = 1.0f;
            return new Slot(
                    o.optInt("previewRotation", 0),
                    o.optInt("captureRotation", 0),
                    o.optBoolean("mirror", false),
                    zoom);
        }
    }

    public int version = CURRENT_VERSION;
    public final Slot[] slots = new Slot[SLOT_COUNT];

    public CaptureConfig() {
        // slot 数组元素在 default / parse 后填充；为安全先填空 Slot
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = new Slot(0, 0, false, 1.0f);
        }
    }

    /**
     * 目标机器实测默认值（与 Camera2Controller 旧版硬编码一致）：
     *   left  : preview 270 / capture 90 / mirror false / zoom 1.0
     *   right : preview 90  / capture 90 / mirror false / zoom 1.0
     */
    public static CaptureConfig defaults() {
        CaptureConfig c = new CaptureConfig();
        c.slots[0] = new Slot(270, 90, false, 1.0f); // left
        c.slots[1] = new Slot(90, 90, false, 1.0f);  // right
        return c;
    }

    public JSONObject toJson() throws JSONException {
        JSONArray arr = new JSONArray();
        for (Slot s : slots) arr.put(s.toJson());
        return new JSONObject()
                .put("version", version)
                .put("slots", arr);
    }

    public static CaptureConfig fromJson(String raw) throws JSONException {
        return fromJson(new JSONObject(raw));
    }

    public static CaptureConfig fromJson(JSONObject root) throws JSONException {
        CaptureConfig c = new CaptureConfig();
        c.version = root.optInt("version", CURRENT_VERSION);
        JSONArray arr = root.optJSONArray("slots");
        if (arr != null) {
            int n = Math.min(arr.length(), SLOT_COUNT);
            for (int i = 0; i < n; i++) {
                JSONObject slotObj = arr.optJSONObject(i);
                if (slotObj != null) {
                    c.slots[i] = Slot.fromJson(slotObj);
                }
            }
            // 数组长度不够时（老配置只有 1 个 slot）剩下的用默认值兜底
            for (int i = n; i < SLOT_COUNT; i++) {
                c.slots[i] = new Slot(0, 0, false, 1.0f);
            }
        } else {
            // 完全找不到 slots 字段 → 用默认
            Slot[] d = defaults().slots;
            for (int i = 0; i < SLOT_COUNT; i++) c.slots[i] = d[i];
        }
        return c;
    }

    /** 用于日志：把当前所有数值压成一行便于核对。 */
    public String debugSummary() {
        StringBuilder sb = new StringBuilder("CaptureConfig v" + version + " ");
        for (int i = 0; i < SLOT_COUNT; i++) {
            Slot s = slots[i];
            sb.append(i == 0 ? "L" : "R")
              .append("[").append(s.previewRotation)
              .append("/").append(s.captureRotation)
              .append(s.mirror ? "/M" : "")
              .append("/z").append(formatZoom(s.zoom))
              .append("] ");
        }
        return sb.toString().trim();
    }

    /** 缩放展示：1.0 → "1.0"，2.5 → "2.5"，避免日志里出现科学计数法。 */
    private static String formatZoom(float z) {
        if (!Float.isFinite(z)) return "1.0";
        // 一位小数；对齐视觉
        return String.format(java.util.Locale.US, "%.1f", z);
    }
}
