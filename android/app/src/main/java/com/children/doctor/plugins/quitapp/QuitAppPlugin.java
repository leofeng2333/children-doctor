package com.children.doctor.plugins.quitapp;

import android.util.Log;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 提供前端主动退出 App 的能力。
 *
 * JS 端通过 {@code QuitApp.exitApp()} 调用，
 * Android 端等价于 {@code getActivity().finish()}，会关闭当前 Activity。
 *
 * 注意：当前 Activity finish() 后，应用进程默认不会被立即杀死，
 * 只是从最近任务列表移除顶层 Activity。如果需要更彻底地退出整个进程，
 * 可以在此基础上追加 {@code System.exit(0)}，但通常 finish() 已足够。
 */
@CapacitorPlugin(name = "QuitApp")
public class QuitAppPlugin extends Plugin {

    private static final String TAG = "QuitAppPlugin";

    @PluginMethod()
    public void exitApp(PluginCall call) {
        Log.d(TAG, "exitApp called");
        call.resolve();
        // 先 resolve promise，再关闭页面，避免 JS 端 onFulfilled 来不及跑。
        // 如果当前 Activity 仍在执行动画 / 异步任务，可在这里加一个 postDelayed。
        getActivity().finish();
    }
}
