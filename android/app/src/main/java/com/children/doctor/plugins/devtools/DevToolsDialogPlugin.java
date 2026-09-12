package com.children.doctor.plugins.devtools;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import com.children.doctor.plugins.dualcamera.CaptureConfig;
import com.children.doctor.plugins.dualcamera.CaptureConfigRepository;
import com.children.doctor.plugins.dualcamera.DualCameraPlugin;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginHandle;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 开发者工具弹窗（原生 Dialog 版）。
 *
 * <p><b>为什么走 native Dialog：</b>
 * Capacitor Android 上 WebView 默认有白色背景，要么 WebView 盖在 preview 之上
 * （弹窗看不到），要么把 preview 推到 WebView 下面（弹窗没打开 WebView 也挡住
 * preview）。两条路都不行。原生 {@link Dialog} 用 WindowManager
 * {@code TYPE_APPLICATION_PANEL}，位于所有 WebView / TextureView / SurfaceView 之上，
 * 同时自身只在 show() 期间占屏，关闭后整张 native window 一起消失，preview
 * 完全不受影响。
 *
 * <p>弹窗里渲染 4 个 slot 的校准控件：左预览 / 右预览（旋转 + 镜像 + 缩放） +
 * 左拍照 / 右拍照（仅旋转）。底部四个按钮：关闭 / 重置默认 / 保存配置 / 退出应用，
 * 中间还有"配置路径"+复制按钮（方便"现场调好 → 拷到另一台同包名设备即用"）。
 *
 * <p>保存：调 {@link DualCameraPlugin#applyCaptureConfigInternal(CaptureConfig)}，
 * 落盘 + 推到当前活跃预览周期。重置默认：同上但传 {@link CaptureConfig#defaults()}。
 * 退出：{@code getActivity().finish()}。
 *
 * <p>JS 调用：
 * <pre>
 *   const result = await DevToolsDialog.show();
 *   // result = { saved: true | false, slots: [...] } —— saved=true 表示用户点了"保存"
 *   // saved=false 表示用户点了"关闭"（或外部返回 / dialog dismiss）
 * </pre>
 *
 * <p>Android 上是 native dialog；web 端走 stub（throw unavailable），
 * 浏览器不需要开发者工具。
 */
@CapacitorPlugin(name = "DevToolsDialog")
public class DevToolsDialogPlugin extends Plugin {

    private static final String TAG = "DevToolsDialogPlugin";

    /** 旋转选项固定为 0/90/180/270，与 CaptureConfig 校验范围一致。 */
    private static final int[] ROTATION_OPTIONS = {0, 90, 180, 270};

    /** EditText 输入框允许的 zoom 区间（UI 限制，与前端 ZOOM_INPUT_MAX 对齐）。 */
    private static final float ZOOM_INPUT_MIN = 1.0f;
    private static final float ZOOM_INPUT_MAX = 4.0f;

    /** 当前活跃 dialog；show() 重复调用时直接 reject 当前 call。 */
    private Dialog currentDialog;

    /**
     * 防重入 / 多次 resolve 兜底。Capacitor PluginCall.resolve 重复调用本身是幂等的，
     * 但这里用标记避免 buildResponse 等重复计算开销，同时保证"按系统返回键 / 外部 dismiss"
     * 时也能 resolve（不会让 JS 端 await 永远挂着）。
     */
    private final java.util.concurrent.atomic.AtomicBoolean resolved = new java.util.concurrent.atomic.AtomicBoolean(false);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 弹出开发者工具 dialog。Android 走 native 实现；web 端 stub reject。
     *
     * <p>返回：
     * <pre>
     *   {
     *     "saved": true | false,
     *     "slots": [ {previewRotation, captureRotation, mirror, zoom}, ... ]
     *   }
     * </pre>
     */
    @PluginMethod()
    public void show(PluginCall call) {
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            call.reject("Activity is not available");
            return;
        }
        mainHandler.post(() -> {
            if (currentDialog != null && currentDialog.isShowing()) {
                // 防重入：直接 reject 当前 call，不挤兑原 dialog。
                call.reject("DevTools dialog is already showing");
                return;
            }
            try {
                showDialogInternal(call);
            } catch (Throwable t) {
                Log.e(TAG, "show failed", t);
                // Capacitor 8 的 PluginCall.reject 重载只有 (String) / (String, JSObject) /
                // (String, Exception) / (String, String)，没有 (String, Throwable) 版本。
                // 这里 stack trace 已经通过 Log.e 打印，reject 第二个参数去掉避免编译错。
                call.reject("Failed to show dev tools dialog: " + t.getMessage());
            }
        });
    }

    private void showDialogInternal(PluginCall call) {
        Activity activity = getActivity();
        if (activity == null) {
            call.reject("Activity is not available");
            return;
        }
        CaptureConfigRepository repo = new CaptureConfigRepository(activity);
        CaptureConfig current = repo.loadOrCreate();
        resolved.set(false);

        Dialog dialog = new Dialog(activity);
        // 默认 Dialog 用系统 theme（含状态栏 / 标题栏）；本 dialog 自管外观。
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // 默认 dimAmount=0.55：弹窗背景半透明黑，preview 仍隐约可见。
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.dimAmount = 0.55f;
            // 弹窗宽度撑满屏幕外侧 24dp 边距，card 在中间
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            // 关键：type 留默认 (TYPE_APPLICATION)，已经在所有 view 之上；
            // 不用 windowSubtract 也不用 FLAG_SECURE。
            window.setAttributes(lp);
        }

        View root = activity.getLayoutInflater().inflate(
                com.children.doctor.R.layout.devtools_dialog, null);
        dialog.setContentView(root);

        TextView status = root.findViewById(com.children.doctor.R.id.dev_status);
        TextView msg = root.findViewById(com.children.doctor.R.id.dev_msg);
        TextView configPath = root.findViewById(com.children.doctor.R.id.dev_config_path);
        Button btnClose = root.findViewById(com.children.doctor.R.id.dev_close);
        Button btnCancel = root.findViewById(com.children.doctor.R.id.btn_cancel);
        Button btnReset = root.findViewById(com.children.doctor.R.id.btn_reset);
        Button btnSave = root.findViewById(com.children.doctor.R.id.btn_save);
        Button btnExit = root.findViewById(com.children.doctor.R.id.btn_exit);
        Button btnCopyPath = root.findViewById(com.children.doctor.R.id.btn_copy_path);

        Spinner spinnerS0PreviewRot = root.findViewById(com.children.doctor.R.id.spinner_s0_preview_rot);
        CheckBox checkS0PreviewMirror = root.findViewById(com.children.doctor.R.id.check_s0_preview_mirror);
        EditText editS0PreviewZoom = root.findViewById(com.children.doctor.R.id.edit_s0_preview_zoom);

        Spinner spinnerS1PreviewRot = root.findViewById(com.children.doctor.R.id.spinner_s1_preview_rot);
        CheckBox checkS1PreviewMirror = root.findViewById(com.children.doctor.R.id.check_s1_preview_mirror);
        EditText editS1PreviewZoom = root.findViewById(com.children.doctor.R.id.edit_s1_preview_zoom);

        Spinner spinnerS0CaptureRot = root.findViewById(com.children.doctor.R.id.spinner_s0_capture_rot);
        CheckBox checkS0CaptureMirror = root.findViewById(com.children.doctor.R.id.check_s0_capture_mirror);
        Spinner spinnerS1CaptureRot = root.findViewById(com.children.doctor.R.id.spinner_s1_capture_rot);
        CheckBox checkS1CaptureMirror = root.findViewById(com.children.doctor.R.id.check_s1_capture_mirror);

        // 旋转选项 string array
        ArrayAdapter<String> rotAdapter = new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_item, new String[]{"0°", "90°", "180°", "270°"});
        rotAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerS0PreviewRot.setAdapter(rotAdapter);
        spinnerS1PreviewRot.setAdapter(rotAdapter);
        spinnerS0CaptureRot.setAdapter(rotAdapter);
        spinnerS1CaptureRot.setAdapter(rotAdapter);

        // 写入当前值（normalize rotation 0/90/180/270 → index 0/1/2/3）
        applyToSpinner(spinnerS0PreviewRot, current.slots[0].previewRotation);
        checkS0PreviewMirror.setChecked(current.slots[0].mirror);
        editS0PreviewZoom.setText(formatZoom(current.slots[0].zoom));

        applyToSpinner(spinnerS1PreviewRot, current.slots[1].previewRotation);
        checkS1PreviewMirror.setChecked(current.slots[1].mirror);
        editS1PreviewZoom.setText(formatZoom(current.slots[1].zoom));

        applyToSpinner(spinnerS0CaptureRot, current.slots[0].captureRotation);
        checkS0CaptureMirror.setChecked(current.slots[0].captureMirror);
        applyToSpinner(spinnerS1CaptureRot, current.slots[1].captureRotation);
        checkS1CaptureMirror.setChecked(current.slots[1].captureMirror);

        // 把当前生效的配置文件绝对路径显示给用户，方便"现场调好 → 拷到另一台同包名设备即用"。
        // Repository 已经在内部解析过路径（externalFilesDir + 一次性 internal 迁移），
        // 这里直接拿 getConfigFile() 即可。
        final String configPathText = repo.getConfigFile().getAbsolutePath();
        if (configPath != null) {
            configPath.setText(configPathText);
        }

        if (status != null) {
            status.setText(activity.getString(com.children.doctor.R.string.devtools_status_idle));
        }

        // 集中处理 resolve，保证"按返回键 / 外部 dismiss / 用户点击按钮"四条路径
        // 都能 resolve 一次（PluginCall 重复 resolve 是幂等的，但这里用 AtomicBoolean
        // 拦截 buildResponse 等重复计算）。
        final Runnable resolveCancel = () -> resolveOnce(call, false, current);

        // 关闭按钮（标题栏 X）：等同"取消"
        View.OnClickListener cancelListener = v -> {
            resolveCancel.run();
            safeDismissDialog();
        };
        btnClose.setOnClickListener(cancelListener);
        btnCancel.setOnClickListener(cancelListener);

        btnSave.setOnClickListener(v -> {
            // 读取控件值
            int s0pRot = spinnerValueToRotation(spinnerS0PreviewRot.getSelectedItemPosition());
            boolean s0pMirror = checkS0PreviewMirror.isChecked();
            float s0pZoom = parseZoom(editS0PreviewZoom, current.slots[0].zoom);

            int s1pRot = spinnerValueToRotation(spinnerS1PreviewRot.getSelectedItemPosition());
            boolean s1pMirror = checkS1PreviewMirror.isChecked();
            float s1pZoom = parseZoom(editS1PreviewZoom, current.slots[1].zoom);

            int s0cRot = spinnerValueToRotation(spinnerS0CaptureRot.getSelectedItemPosition());
            boolean s0cMirror = checkS0CaptureMirror.isChecked();
            int s1cRot = spinnerValueToRotation(spinnerS1CaptureRot.getSelectedItemPosition());
            boolean s1cMirror = checkS1CaptureMirror.isChecked();

            CaptureConfig next = new CaptureConfig();
            next.slots[0] = new CaptureConfig.Slot(s0pRot, s0cRot, s0pMirror, s0cMirror, s0pZoom);
            next.slots[1] = new CaptureConfig.Slot(s1pRot, s1cRot, s1pMirror, s1cMirror, s1pZoom);

            // 保存路径：优先复用 DualCameraPlugin（落盘 + 推给活跃 preview）。
            // 兜底：如果用户没启动过 preview，DualCameraPlugin 可能尚未加载，
            // 此时直接走 CaptureConfigRepository.save() 落盘即可（下次 startPreview
            // 会自然读到新值，跟冷启动语义一致）。
            DualCameraPlugin dualCam = findDualCameraPlugin();
            try {
                CaptureConfig after;
                if (dualCam != null) {
                    after = dualCam.applyCaptureConfigInternal(next);
                } else {
                    // 没有活跃 DualCameraPlugin（用户没启动过 preview）时直接走 repository 落盘。
                    // 复用顶部已创建的 repo 实例，避免同名局部变量重复声明。
                    repo.save(next);
                    after = repo.loadOrCreate();
                    Log.i(TAG, "saved without DualCameraPlugin (preview not yet started): "
                            + after.debugSummary());
                }
                showMsg(activity, msg, activity.getString(com.children.doctor.R.string.devtools_saved), false);
                // 立即关闭 dialog，让用户看到 preview 已经在新配置下渲染（如果有活跃 preview）。
                resolveOnce(call, true, after);
                safeDismissDialog();
            } catch (Throwable t) {
                Log.e(TAG, "save failed", t);
                showMsg(activity, msg, "保存失败: " + t.getMessage(), true);
            }
        });

        btnExit.setOnClickListener(v -> {
            // 复用首页退出路径，与 QuitAppPlugin 一致。
            try {
                resolveCancel.run();
                safeDismissDialog();
            } finally {
                // 即便上面 resolve 抛异常也要保证 Activity 关闭。
                activity.finish();
            }
        });

        // 重置默认：落盘 defaults + 推给活跃预览周期（与 save 同路径，但不走 UI 控件值）。
        btnReset.setOnClickListener(v -> {
            DualCameraPlugin dualCam = findDualCameraPlugin();
            try {
                CaptureConfig defaults = CaptureConfig.defaults();
                CaptureConfig after;
                if (dualCam != null) {
                    after = dualCam.applyCaptureConfigInternal(defaults);
                } else {
                    // 没有活跃 preview 时直接走 repository 落盘（语义同 save 的兜底分支）。
                    repo.save(defaults);
                    after = repo.loadOrCreate();
                    Log.i(TAG, "reset without DualCameraPlugin: " + after.debugSummary());
                }
                // 把控件值刷成 defaults，让 UI 跟磁盘一致（避免显示 stale 状态）
                applyToSpinner(spinnerS0PreviewRot, after.slots[0].previewRotation);
                checkS0PreviewMirror.setChecked(after.slots[0].mirror);
                editS0PreviewZoom.setText(formatZoom(after.slots[0].zoom));
                applyToSpinner(spinnerS1PreviewRot, after.slots[1].previewRotation);
                checkS1PreviewMirror.setChecked(after.slots[1].mirror);
                editS1PreviewZoom.setText(formatZoom(after.slots[1].zoom));
                applyToSpinner(spinnerS0CaptureRot, after.slots[0].captureRotation);
                checkS0CaptureMirror.setChecked(after.slots[0].captureMirror);
                applyToSpinner(spinnerS1CaptureRot, after.slots[1].captureRotation);
                checkS1CaptureMirror.setChecked(after.slots[1].captureMirror);

                showMsg(activity, msg,
                        activity.getString(com.children.doctor.R.string.devtools_reset_done), false);
            } catch (Throwable t) {
                Log.e(TAG, "reset failed", t);
                showMsg(activity, msg, "重置失败: " + t.getMessage(), true);
            }
        });

        // 复制路径：用户拷贝后能直接 adb pull / push，或用 MTP 找到该文件。
        btnCopyPath.setOnClickListener(v -> {
            try {
                ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("capture_config_path", configPathText));
                    showMsg(activity, msg,
                            activity.getString(com.children.doctor.R.string.devtools_path_copied), false);
                } else {
                    showMsg(activity, msg, "剪贴板不可用", true);
                }
            } catch (Throwable t) {
                Log.w(TAG, "copy path failed: " + t.getMessage());
                showMsg(activity, msg, "复制失败: " + t.getMessage(), true);
            }
        });

        dialog.setOnDismissListener(d -> {
            // 用户按系统返回键 / 外部 dismiss 时也能保证 promise resolve 一次。
            resolveCancel.run();
            currentDialog = null;
        });
        dialog.setOnCancelListener(d -> {
            currentDialog = null;
        });

        currentDialog = dialog;
        dialog.show();
    }

    private void resolveOnce(PluginCall call, boolean saved, CaptureConfig cfg) {
        if (resolved.compareAndSet(false, true)) {
            call.resolve(buildResponse(saved, cfg));
        }
    }

    /**
     * 把对话框 dismiss 掉（如果还在显示）。
     * dismiss 过程中可能抛 BadTokenException（Activity 已销毁），吞掉。
     */
    private void safeDismissDialog() {
        Dialog d = currentDialog;
        if (d == null) return;
        try {
            if (d.isShowing()) d.dismiss();
        } catch (Throwable t) {
            Log.w(TAG, "dismiss failed: " + t.getMessage());
        }
        currentDialog = null;
    }

    /** 把 rotation 写入 spinner；非法值兜底为 0。 */
    private void applyToSpinner(Spinner spinner, int rotation) {
        int idx = 0;
        for (int i = 0; i < ROTATION_OPTIONS.length; i++) {
            if (ROTATION_OPTIONS[i] == rotation) { idx = i; break; }
        }
        spinner.setSelection(idx);
    }

    private int spinnerValueToRotation(int pos) {
        if (pos < 0 || pos >= ROTATION_OPTIONS.length) return 0;
        return ROTATION_OPTIONS[pos];
    }

    /** 解析 zoom 输入；空 / NaN / 越界回退到 fallback。 */
    private float parseZoom(EditText edit, float fallback) {
        String raw = edit.getText() != null ? edit.getText().toString().trim() : "";
        if (raw.isEmpty()) return fallback;
        try {
            float v = Float.parseFloat(raw);
            if (!Float.isFinite(v)) return fallback;
            if (v < ZOOM_INPUT_MIN) v = ZOOM_INPUT_MIN;
            if (v > ZOOM_INPUT_MAX) v = ZOOM_INPUT_MAX;
            return Math.round(v * 10f) / 10f;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String formatZoom(float z) {
        if (!Float.isFinite(z)) return "1.0";
        return String.format(java.util.Locale.US, "%.1f", z);
    }

    private void showMsg(Activity activity, TextView msg, String text, boolean isError) {
        if (msg == null) return;
        msg.setText(text);
        msg.setVisibility(View.VISIBLE);
        msg.setBackgroundColor(isError
                ? 0xFFFDEEEF
                : 0xFFE6F6EC);
        msg.setTextColor(isError
                ? 0xFFB00020
                : 0xFF1A7F37);
    }

    /** 把 plugin call 收到的配置序列化成 { saved, slots } 返回。 */
    private JSObject buildResponse(boolean saved, CaptureConfig cfg) {
        JSObject ret = new JSObject();
        ret.put("saved", saved);
        JSArray arr = new JSArray();
        for (int i = 0; i < CaptureConfig.SLOT_COUNT; i++) {
            CaptureConfig.Slot s = cfg.slots[i];
            JSObject o = new JSObject();
            o.put("previewRotation", s.previewRotation);
            o.put("captureRotation", s.captureRotation);
            o.put("mirror", s.mirror);
            o.put("captureMirror", s.captureMirror);
            o.put("zoom", s.zoom);
            arr.put(o);
        }
        ret.put("slots", arr);
        return ret;
    }

    /**
     * 通过 bridge 拿到 DualCameraPlugin 引用。
     * Capacitor 8 的 Bridge.getPlugin(String) 返回 PluginHandle（不是 Plugin），
     * 需要拿真实 Plugin 实例要 .getInstance()，再 instanceof 一次。
     */
    private DualCameraPlugin findDualCameraPlugin() {
        try {
            PluginHandle handle = getBridge().getPlugin("DualCamera");
            if (handle != null) {
                Plugin instance = handle.getInstance();
                if (instance instanceof DualCameraPlugin) {
                    return (DualCameraPlugin) instance;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "findDualCameraPlugin failed: " + t.getMessage());
        }
        return null;
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        safeDismissDialog();
    }
}
