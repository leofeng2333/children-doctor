# Plan: 打印流程 children-doctor vs sampleAPK 对比

> 状态：**文档生成完成**（2026-08-25）
> 目的：把现有项目的 HiTi 打印流程与 SDK 自带 sampleAPK 横向对照，列出所有差异，便于后续修复决策。
> 对照文件：
>
> | 项目 | 路径 |
> |------|------|
> | children-doctor（主项目） | `android/app/src/main/java/com/children/doctor/plugins/hitiprinter/{HiTiPrinterManager.java, HiTiPrinterPlugin.java}` + `src/utils/print.ts` |
> | sampleAPK（vendor 参考） | `Android_Rolltype_UsbLinkSdk_Release/sampleAPK/app/src/main/java/com/hiti/test/{MainActivity.java, PrinterOperation.java}` |

---

## 1. 背景与目标

### 1.1 现象

- sampleAPK 的 `b_printPhoto` 按钮触发 USB_PRINT_PHOTOS 能稳定打印。
- children-doctor 打印失败：`print_logs/print_20260824_220102_571.log` 与 `print_20260824_220417_412.log` 显示 `doService` 调用后**日志在第 30 行停住**，无任何 `doService returned` 输出，UI 25s `Promise.race` 超时后才调 `releaseForPage`。

### 1.2 目标

逐行对照两个项目的打印链路，把所有"我们做了但 sample 没做"的差异列清楚，判断哪些差异是必要的、哪些是反向适配、哪些是导致卡死的真凶。

---

## 2. 整体打印链路对照

```
┌─────────────────────────────────┬─────────────────────────────────┐
│ sampleAPK（vendor 参考）        │ children-doctor（主项目）        │
├─────────────────────────────────┼─────────────────────────────────┤
│ MainActivity b_printPhoto click │ TS print.ts: printPhoto()       │
│         ↓                       │         ↓                       │
│ operatePrinter(Action.USB_      │ HiTiPrinterPlugin.printPhoto()  │
│   PRINT_PHOTOS)                 │   base64 → ExternalCache        │
│         ↓                       │         ↓                       │
│ operation.PaperType = ...       │ HiTiPrinterManager.printPhoto() │
│ operation.m_strTablesRoot = ... │   SamplePrintOptions            │
│ operation.m_strTablesRoot = ""  │         ↓                       │
│         ↓                       │   raw Thread {                  │
│ PrinterOperation.print(path)    │     serviceConnector.doService()│
│         ↓                       │   }                             │
│ printerSetService → doService   │         ↓                       │
│         ↓                       │   PrintPara.getPrintPhotoPara   │
│ PrintPara.getPrintPhotoPara     │         ↓                       │
│ (raw bitmap from drawable)      │   SDK USB transfer              │
│         ↓                       │                                 │
│ SDK USB transfer                │                                 │
└─────────────────────────────────┴─────────────────────────────────┘
```

对照目标：每一节点的参数、线程、回调、错误处理。

---

## 3. 节点级对比表

### 3.1 入口与调用线程

| 维度 | sampleAPK | children-doctor |
|------|-----------|-----------------|
| 入口位置 | `MainActivity.onClick` → `operatePrinter()` | `HiTiPrinterPlugin.printPhoto()` (Capacitor bridge) → `HiTiPrinterManager.printPhoto()` |
| 调用线程 | UI 线程（onClick 本来就在 main）| Capacitor 在 main 线程调用，但 `HiTiPrinterManager.printPhoto()` 内部 `new Thread().start()` 启动 raw Thread |
| 阻塞性 | `operatePrinter` 用 `new Thread(task).start()` 起 worker | 同样 raw Thread（见 `HiTiPrinterManager.java:357`）|
| 区别 | **无实质差异** | 同左 |

```java
// sampleAPK MainActivity.java:553-560
void operatePrinter(final Action action) {
    Thread task = new Thread(){
        PrinterJob job = null;
        @Override public void run() {
            ...
        }
    };
}
```

```java
// children-doctor HiTiPrinterManager.java:253-358
Thread sampleThread = new Thread(new Runnable() {
    @Override public void run() {
        try {
            ...
            serviceConnector.doService(job);  // 同步
        } catch (Throwable t) {
            ...
        }
    }
}, "HiTi-PrintPhoto-" + System.currentTimeMillis());
sampleThread.start();
```

### 3.2 ServiceConnector 初始化

| 维度 | sampleAPK | children-doctor |
|------|-----------|-----------------|
| 时机 | `onCreate` 时调一次 `ServiceConnector.register(this, null)` | 页面级 `initForPage` / `releaseForPage`（避开 UVC camera 端口冲突）|
| 反注册 | sample 没显式 `unregister` | `releaseForPage` 里调 `serviceConnector.unregister()` |
| 区别 | sample 注册后不释放，进程退出才释放 | **我们改对了**：app 启动后只占 USB 给打印页面，避免 UVC 被抢占 |
| 副作用 | sample 不能同时跑 camera | 我们同时跑 camera + 打印 |

```java
// sampleAPK MainActivity.java:148
serviceConnector = ServiceConnector.register(this, null);
```

```java
// children-doctor HiTiPrinterManager.java:140-161（init）
public void init() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
        mainHandler.post(this::init);
        return;
    }
    if (serviceConnector == null) {
        serviceConnector = ServiceConnector.register(context, null);
    }
    io.execute(this::ensureTablesExtracted);
}
```

### 3.3 打印参数装配

| 维度 | sampleAPK | children-doctor |
|------|-----------|-----------------|
| 字段传递方式 | `operation.PaperType / MATTE / PRINTCOUNT / PRINTMODE` 实例字段（`PrinterOperation.java:25-27`）| `SamplePrintOptions` POJO（`HiTiPrinterManager.java:481-507`）|
| 默认值 | `PaperType=4`（6×8）、`MATTE=1`、`PRINTCOUNT=1`、`PRINTMODE=1`（`PrinterOperation.java:31-34`）| `paperType=2`（4×6）、`matte=1`、`printCount=1`、`printMode=0`（`HiTiPrinterManager.java:268-275`）|
| PaperSize switch | `case 2..6` 显式枚举 | 同款枚举（`HiTiPrinterManager.java:404-412`）|
| 区别 | PRINTMODE 默认值不同（1 vs 0）| 我们在用户调 `printPhoto` 时显式传 `printMode=0`（HiTiPrinterPlugin.java:272）|

实际打印调用通过 PaperSize 的 case 2 (PAPER_SIZE_6X4_PHOTO) 走，与 sample 同尺寸。无差别。

### 3.4 Bitmap 来源与预处理 ⚠️ **核心差异区**

| 维度 | sampleAPK | children-doctor | 影响 |
|------|-----------|-----------------|------|
| Bitmap 来源 | `res/drawable-hdpi/photo*.jpg` drawable 资源 | TS canvas → `data:image/jpeg;base64` → native 解 base64 写文件 → `BitmapFactory.decodeFile` |
| 解码方式 | `BitmapFactory.decodeStream(openRawResource(id))` | `BitmapFactory.decodeFile(bitmapPath)` |
| 文件落盘 | 无（drawable 直接读）| 写到 `getExternalCacheDir()/hiti_print_*.jpg`（`HiTiPrinterPlugin.java:298-302`）|
| **TS 端 cover-fit** | ❌ 不做 | ✅ `fitImageToPaper` 输出 1536×1024 landscape |
| **Java 端 portrait 旋转** | ❌ 不做 | ✅ 检测 portrait bitmap 旋转 90°（`HiTiPrinterManager.java:419-430`）|
| **Java 端 normalize** | ❌ 不做 | ✅ `normalizeBitmapToPaperSize` 按 PaperSize 比例 center-crop（`HiTiPrinterManager.java:455-...`）|
| Bitmap 尺寸 | sample bitmap 已经按 PaperSize 物理像素预备（photo3: 1844×1240）| 我们强行 cover-fit 到 1536×1024 |

**为什么 sample 不做预处理**：sample bitmap 已经是 1844×1240 / 1240×1844 等物理像素，与 PaperSize 期望尺寸一致（参见 `PrinterOperation.java:206-224` 的配图表）。SDK 内部不需要再 scale。

**我们的反向适配**：原图比例不可控 → TS cover-fit → Java 二次处理。但 sampleAPK 不需要这种适配也能打印。

### 3.5 m_strTablesRoot 时序

| 维度 | sampleAPK | children-doctor |
|------|-----------|-----------------|
| 设置位置 | `MainActivity.java:651-652` 进入 doService 前 | `HiTiPrinterManager.java:305-306` 进入 doService 前 |
| 清空位置 | `MainActivity.java:655` doService 后立即清 | `HiTiPrinterManager.java:316 / 325` doService 后立即清（无论成功失败）|
| 是否成对 | ✅ 成对（set → doService → reset）| ✅ 成对（finally 块保证 reset）|
| 区别 | 直接对 `operation.m_strTablesRoot` 操作 | 对 `serviceConnector.m_strTablesRoot` 直接操作（绕过 operation 字段）|

```java
// sampleAPK MainActivity.java:647-655
case USB_PRINT_PHOTOS:
    operation.PRINTCOUNT = PRINTCOUNT;
    operation.MATTE = MATTE;
    operation.PRINTMODE = PRINTMODE;
    operation.PaperType = PaperType;
    operation.m_strTablesRoot = m_strTablesRoot;
    job = operation.print(mSelectedPath);
    operation.m_strTablesRoot = "";
    break;
```

```java
// children-doctor HiTiPrinterManager.java:305-327（当前）
logD("printPhoto: pre-set serviceConnector.m_strTablesRoot='" + tablesRoot + "'");
serviceConnector.m_strTablesRoot = tablesRoot;

doServiceWithTimeout(job, new Callback<String>() {
    @Override public void onSuccess(String errStr) {
        String before = serviceConnector.m_strTablesRoot;
        serviceConnector.m_strTablesRoot = "";
        ...
    }
    @Override public void onError(String error) {
        serviceConnector.m_strTablesRoot = "";
        ...
    }
});
```

**实质差异**：sample 在 raw Thread 里同步 doService（同步清空）；我们通过 `doServiceWithTimeout` 异步清空。

### 3.6 doService 调用方式 🐛 **关键 bug 区**

| 维度 | sampleAPK | 8e19301 之前（a3fd715）| 8e19301 之后（已撤销）| ff601d5（当前） |
|------|-----------|----------------------|---------------------|-----------------|
| doService 调用 | raw Thread 同步 `serviceConnector.doService(job)` | raw Thread 调 `doServiceWithTimeout` | raw Thread 同步 `doService`（**无兜底**）| 恢复 `doServiceWithTimeout` 兜底 |
| 兜底超时 | ❌ 无（依赖 Android USB transfer timeout）| ✅ 20s future.get timeout | ❌ 无 | ✅ 20s future.get timeout |
| 错误处理 | 同步读 `job.errCode` + UI TextView 追加 | errCode 透传 + 异常捕获 | errCode 透传 | errCode 透传 |
| 区别 | sample 不需要超时是因为 USB 设备稳定连接 | 我们有兜底 | **撤销兜底是 8e19301 引入的 bug** | 恢复兜底 |

```java
// sampleAPK PrinterOperation.java:308-316
private PrinterJob printerSetService(Action action, Object data) {
    PrinterJob job = null;
    if(action != null) {
        job = new PrinterJob(mJobId++, action).setJobPara(getPrinterPara(action, data));
        serviceConnector.doService(job);  // 同步调用，无 timeout
    }
    return job;
}
```

```java
// children-doctor 8e19301 后（已撤销）HiTiPrinterManager.java:313-323
serviceConnector.m_strTablesRoot = tablesRoot;
logD("printPhoto: calling serviceConnector.doService synchronously on " + Thread.currentThread().getName());
try {
    serviceConnector.doService(job);  // 同步，无 timeout 兜底
} finally {
    serviceConnector.m_strTablesRoot = "";
}
```

```java
// children-doctor 当前（ff601d5）HiTiPrinterManager.java:313-328
serviceConnector.m_strTablesRoot = tablesRoot;
doServiceWithTimeout(job, new Callback<String>() {
    @Override public void onSuccess(String errStr) {
        serviceConnector.m_strTablesRoot = "";
        ...
    }
    @Override public void onError(String error) {
        serviceConnector.m_strTablesRoot = "";
        ...
    }
});
```

**根因**：8e19301 注释写"实测能正常返回"，但生产日志（`print_20260824_220102_571.log` 行 30）显示 doService 卡死 17s+ 不返回。撤销 8e19301、恢复 a3fd715 的 `doServiceWithTimeout` 兜底。

### 3.7 错误处理与回调

| 维度 | sampleAPK | children-doctor |
|------|-----------|-----------------|
| 错误码读取 | `job.errCode.value` + `job.errCode.description`（`MainActivity.java:825-836`）| 同款（`HiTiPrinterManager.java:374-379` `retrieveSampleData`）|
| 错误信息格式 | `\n\n<<<USB_PRINT_PHOTOS -ID<id> : err <0x<hex> <desc>>` | 同款（`HiTiPrinterManager.java:369-384`）|
| 异常处理 | 同步 try-catch（`MainActivity.java:821-822`）| 同步 try-catch + `doServiceWithTimeout` TimeoutException 捕获（`HiTiPrinterManager.java:662-684`）|
| 超时错误 | 无 | `cb.onError("HiTi " + job.action + " timed out after " + timeoutSeconds + "s ...")` |
| 区别 | sample 用 UI TextView 追加显示 | 我们用 Capacitor bridge 返回 `{ok: false, error: ...}` |

### 3.8 TS 端入口

| 维度 | sampleAPK | children-doctor |
|------|-----------|-----------------|
| 入口 | 无 TS 层（纯 Java）| `printPhoto()` in `src/utils/print.ts` |
| 图像准备 | 无（drawable 已准备好）| `fitImageToPaper()` 强制 cover-fit 到 1536×1024 landscape |
| base64 传输 | 无 | `HiTiPrinterPlugin.printPhoto()` 接收 base64，写盘后调 `manager.printPhoto` |
| 兜底超时 | 无 | TS 端 `Promise.race` 25s（比 native 20s 多 5s）|
| 区别 | sample 是 reference app，没 TS 层 | 我们加 TS 层做图像准备，但做了不必要的反向适配 |

```typescript
// children-doctor src/utils/print.ts:309-330
await Promise.race([
  (async () => {
    const resolved = await fitImageToPaper(opts.goodImgUrl)
    ...
    const photoRes = await HiTiPrinter.printPhoto({ base64: ..., paperType, ... })
    ...
  })(),
  new Promise<...>((_, reject) =>
    setTimeout(() => reject(new Error('HiTi print timeout after 25s')), 25_000),
  ),
])
```

### 3.9 线程模型

| 维度 | sampleAPK | children-doctor |
|------|-----------|-----------------|
| UI 线程 | onClick 直接触发 | Capacitor bridge onPluginMethod（main thread）→ raw Thread |
| doService 线程 | raw Thread（`operatePrinter` 内 `new Thread()`）| raw Thread（`HiTiPrinterManager.printPhoto()` 内 `new Thread()`）|
| 专用 executor | 无 | `printExecutor`（`HiTiPrinterManager.java:77-81`）专跑 doService |
| 状态探测线程 | raw Thread | `io` executor（`HiTiPrinterManager.java:58-62`）单线程 |
| 区别 | sample 简单，UI 线程只 spawn worker | 我们多了双 executor 设计（io / printExecutor），是为了状态探测和打印隔离避免死锁 |

---

## 4. 关键差异汇总

| # | 差异 | sampleAPK 行为 | children-doctor 行为 | 影响 |
|---|------|---------------|---------------------|------|
| 1 | Bitmap 来源 | drawable 资源（已按 PaperSize 物理像素预备）| TS canvas cover-fit → 文件 → BitmapFactory.decodeFile | 流程更长 |
| 2 | **TS 端 cover-fit** | ❌ 不做 | ✅ 1536×1024 landscape（`print.ts:185-...` `fitImageToPaper`）| **强制 portrait → landscape，导致竖图被横打**（详见 #3）|
| 3 | **Java 端 portrait 旋转** | ❌ 不做 | ✅ 旋转 90° + normalize crop（`HiTiPrinterManager.java:418-439`）| **修复竖图方向**（510e621）|
| 4 | **doService 兜底超时** | ❌ 无 | ✅ 20s future.get（`HiTiPrinterManager.java:651-684` `doServiceWithTimeout`）| **修复卡死**（ff601d5 恢复 a3fd715 设计）|
| 5 | ServiceConnector 生命周期 | onCreate 注册 → 进程退出释放 | 页面级 init/release（避开 UVC camera USB 冲突）| ✓ 必要改动 |
| 6 | 参数传递方式 | 实例字段（`PrinterOperation`）| POJO + 实例字段 | 实现细节差异 |
| 7 | 错误处理 | UI TextView 追加 | Capacitor bridge 返回 `{ok, data/error}` | 实现差异 |
| 8 | TS Promise.race 兜底 | 无 | 25s race（`print.ts:309-...`）| 二次保险 |
| 9 | 默认 PRINTMODE | 1（fine mode）| 0（standard mode）| 实际打印调用显式传 0，无影响 |
| **10** | **`USB_CHECK_PRINTER_STATUS` 预检** | ✅ **持续探测**（ClockTask 3s/次，StartService 后立刻 schedule）| ❌ **不做预检**（`print.ts:343-346`）| **疑似根因：冷启动 USB 通道未热身** |
| **11** | **`StopService` 调用** | ✅ **显式调用**（b_stopService onClick）| ❌ **不调用**（release 只 unregister）| **疑似影响：service 状态可能不一致** |
| **12** | **`serviceConnector.m_strTablesRoot` 在 PRINT 路径** | ❌ **不设**（保持 `""`，`PrinterOperation.java:308-316` printerSetService 内部不设）| ✅ **设成 tablesRoot**（`HiTiPrinterManager.java:306`）| **疑似影响：SDK 内部对 m_strTablesRoot 的行为可能不同** |

---

## 5. SDK API 调用差异（应用代码层对比）

> 本节只看两边 app 自己的代码——`HiTiPrinterManager.java` / `HiTiPrinterPlugin.java` / `print.ts`
> vs `MainActivity.java` / `PrinterOperation.java`——列出**调用了哪些 SDK 方法、调用多少次、什么线程、什么参数**。
> 不展开 SDK 字节码或内部实现，仅基于应用代码层判定哪些差异可能导致功能问题。

### 5.1 SDK 方法调用清单

| SDK 方法 | sampleAPK 调用位置 | children-doctor 调用位置 | 是否对称 |
|---------|-------------------|----------------------|---------|
| `ServiceConnector.register(context, null)` | `MainActivity.java:148`（onCreate）| `HiTiPrinterManager.java:149`（init, main thread）| ✅ 一致 |
| `ServiceConnector.unregister()` | `MainActivity.java:302`（onDestroy）| `HiTiPrinterManager.java:170`（release, main thread）| ✅ 一致 |
| `serviceConnector.StartService()` | `MainActivity.java:321`（b_startService onClick）| `HiTiPrinterManager.java:526`（startService, main thread）| ✅ 一致 |
| `serviceConnector.StopService()` | `MainActivity.java:336`（b_stopService onClick）| `HiTiPrinterManager.java:545`（stopService, main thread）| ✅ 一致 |
| `serviceConnector.doService(job)` | `PrinterOperation.java:299 / 313 / 328`（raw Thread）| `HiTiPrinterManager.java:633`（printExecutor via doServiceWithTimeout）| ✅ 调用点一致，线程不同 |
| `serviceConnector.m_strTablesRoot = ...` | `PrinterOperation.java:298`（raw Thread，**仅 printerService 路径**，**不含 PRINT_PHOTOS**）| `HiTiPrinterManager.java:306 / 582`（print / runOp 路径）| ⚠️ **sample 在 PRINT_PHOTOS 路径不设，children-doctor 设了** |
| `operation.m_strTablesRoot = ...` | `MainActivity.java:212 / 567 / 652`（ClockTask + operatePrinter case，含 PRINT_PHOTOS）| 不使用（无 operation 字段）| — 字段级差异 |
| `PrintPara.getPrintPhotoPara(bmp, count, matte, mode, size, tablesRoot)` | `PrinterOperation.java:248 / 252 / 256 / 260 / 265 / 269` | `HiTiPrinterManager.java:419`（6 参数重载）| ✅ 同一重载，参数顺序一致 |
| `PrintPara.getSetCommandPara(short)` | `PrinterOperation.java:278`（USB_SET_AUTO_POWER_OFF）| 未实现 | — 非打印必需 |

### 5.2 USB 命令（Action）覆盖度

| Action | sampleAPK 入口 | children-doctor 入口 | 打印必需 |
|--------|---------------|----------------------|---------|
| **USB_CHECK_PRINTER_STATUS** | **ClockTask.scheduleAtFixedRate 3s/次 + b_serviceStatus** | `getPrinterStatus()`（5s 短超时）| **疑似必需**：sample 持续，children-doctor 单次 + 不预检 |
| USB_DEVICE_MODEL_NAME | b_printerInfo | `getModelName()` | 否 |
| USB_DEVICE_SERIAL_NUM | b_printerInfo | `getSerialNumber()` | 否 |
| USB_DEVICE_FW_VERSION | b_printerInfo | `getFirmwareVersion()` | 否 |
| USB_DEVICE_RIBBON_INFO | b_printerInfo | `getRibbonInfo()` | 否 |
| USB_DEVICE_PRINT_COUNT | b_printerInfo | `getPrintCount()` | 否 |
| USB_COMMAND_RESET_PRINTER | b_resetPrinter | `resetPrinter()` | 否 |
| USB_COMMAND_CALIBRATE_RIBBON_LED | b_calibrateRibbonLED | ❌ 未实现 | 否 |
| USB_COMMAND_RESUME_JOB | b_resumeJob | `resumeJob()` | 否 |
| USB_EJECT_PAPER_JAM | b_ejectPaperJam | `ejectPaperJam()` | 否 |
| USB_COMMAND_CLEAN_PAPER_PATH | b_cleanPaperPath | ❌ 未实现 | 否 |
| **USB_PRINT_PHOTOS** | **b_printPhoto** | **printPhoto()** | ✅ 必需 |
| USB_SET_AUTO_POWER_OFF | b_setAutoPowerOff | ❌ 未实现 | 否 |
| USB_GET_STORAGE_ID 等 PTP 操作 | b_getObjectID 等 | ❌ 未实现 | 否 |
| USB_COMMAND_UPDATE_FW | b_updateFW | ❌ 未实现 | 否 |

**只有 2 个 Action 是打印相关且行为不一致**：

#### 5.2.1 USB_CHECK_PRINTER_STATUS 🐛 **疑似根因之一**

| 维度 | sampleAPK | children-doctor |
|------|-----------|-----------------|
| 调用时机 | `StartService()` 之后**立刻** schedule `ClockTask`，3 秒一次持续调 | 主动 `getPrinterStatus()` 才调，5s 短超时 |
| 打印流程是否预检 | ✅ 是（ClockTask 已经在跑）| ❌ 否（`print.ts:343-346` 注释明确"不做预检"）|
| 每次调用前的 tablesRoot set | ✅ 是（`MainActivity.java:567`）| ✅ 是（`HiTiPrinterManager.java:582` `runOp`）|
| 区别 | 持续 keepalive + 探测 | 单次 + 不预检 |

```java
// sampleAPK MainActivity.java:320-325
case R.id.b_startService:
    errorCode = serviceConnector.StartService();
    exec3 = Executors.newSingleThreadScheduledExecutor();
    exec3.scheduleAtFixedRate(new ClockTask(), 3000, 3000, TimeUnit.MILLISECONDS);
    break;
```

```java
// sampleAPK MainActivity.java:208-218（ClockTask 定义）
private class ClockTask extends TimerTask {
    @Override public void run() {
        PrinterJob job = null;
        operation.m_strTablesRoot = m_strTablesRoot;  // 每次前 set
        job = operation.getPrinterStatus();           // 每次 USB_CHECK_PRINTER_STATUS
        ...
    }
}
```

```typescript
// children-doctor print.ts:343-346
// 不做 getPrinterStatus 预检：HiTi SDK 没有不调 USB transfer 的轻量探测，
// 预检本身会再发一次 USB_CHECK_PRINTER_STATUS 卡住直到 native 兜底超时，
// 体感上跟直接打一样卡，而且错误信息被预检吃掉一层更难看。
console.log('[print/HiTi] skipping getPrinterStatus pre-check, going straight to print')
```

**潜在影响**：

- sample 持续探测 = USB control transfer 通道始终活跃，第一次 `USB_PRINT_PHOTOS` 走的是"已经热身的 USB 通道"。
- children-doctor 不预检 = 冷启动第一次 `USB_PRINT_PHOTOS` 直接打，USB control transfer 通道可能没热身，SDK 内部可能进入异常分支（USB endpoint stall / NAK 等），进而导致 doService 卡死或 timeout。

#### 5.2.2 USB_PRINT_PHOTOS — `m_strTablesRoot` set 时机 ⚠️ **潜在 bug**

| 维度 | sampleAPK | children-doctor |
|------|-----------|-----------------|
| `operation.m_strTablesRoot = m_strTablesRoot`（**PRINT 之前**）| ✅ 是（`MainActivity.java:652`）| — 不适用（无 operation 字段）|
| `serviceConnector.m_strTablesRoot = m_strTablesRoot`（**PRINT 之前**）| ❌ 否（`PrinterOperation.printerSetService` 行 308-316 没 set）| ✅ 是（`HiTiPrinterManager.java:306`）|
| `serviceConnector.m_strTablesRoot = ""`（**PRINT 之后**）| — 不需要（printerSetService 内部没改）| ✅ 是（`HiTiPrinterManager.java:316 / 325`，onSuccess/onError 两个分支）|
| `PrintPara.getPrintPhotoPara(..., m_strTablesRoot)` | ✅ 是（行 248）| ✅ 是（行 419，传 `tablesRootForPara`）|

**观察**：

- sample 在 PRINT 路径**只**通过 `operation.m_strTablesRoot` 设置，且 `PrinterOperation.print → printerSetService` **不**改 `serviceConnector.m_strTablesRoot`（行 313 之前没看到 `serviceConnector.m_strTablesRoot = ...`）。
- children-doctor 在 PRINT 路径**同时**设了 `serviceConnector.m_strTablesRoot`（行 306）和传 `tablesRootForPara` 给 PrintPara（行 419）。

**潜在影响**：

- sample 是"双轨但只走一轨"——只设 `operation.m_strTablesRoot`，`serviceConnector.m_strTablesRoot` 保持 onCreate 时 set 的 `""`（`MainActivity.java:153`）。
- children-doctor 是"双轨且都走"——`serviceConnector.m_strTablesRoot` 设成 `tablesRoot`（已 extracted 的目录），doService 完成后清成 `""`。
- **如果 SDK 内部对 `serviceConnector.m_strTablesRoot` 在 PRINT_PHOTOS 路径有特殊行为**，children-doctor 设的 `tablesRoot` 与 sample 的 `""` 行为可能不同。具体 SDK 内部行为需要 vendor 确认，应用代码层无法判定。

### 5.3 调用顺序对照

| 顺序 | sampleAPK | children-doctor | 是否一致 |
|------|-----------|----------------|---------|
| 1 | `ServiceConnector.register`（onCreate）| `initForPage → manager.init()` | ✅ |
| 2 | 用户点 b_startService | TS `step 1/2: startService` | ✅ |
| 3 | `StartService()` | `startService()` | ✅ |
| 4 | `ClockTask.scheduleAtFixedRate`（持续 3s/次 USB_CHECK_PRINTER_STATUS）| **无** | ❌ |
| 5 | 用户选 photo path | `fitImageToPaper → base64 → ExternalCacheDir/hiti_print_*.jpg` | ✅（实现路径不同）|
| 6 | `operation.m_strTablesRoot = m_strTablesRoot` | `serviceConnector.m_strTablesRoot = tablesRoot` | ⚠️ 不同对象字段 |
| 7 | `PrintPara.getPrintPhotoPara(bitmap, count, matte, mode, size, m_strTablesRoot)` | `PrintPara.getPrintPhotoPara(bitmap, count, matte, mode, size, tablesRootForPara)` | ✅ |
| 8 | `serviceConnector.doService(job)` 同步 | `doServiceWithTimeout(job, cb)` 异步（20s future.get）| ⚠️ 同步 vs 异步 |
| 9 | `job.errCode` 透传到 UI TextView | `cb.onSuccess` / `cb.onError` → Capacitor bridge → TS Promise | ✅ |
| 10 | `operation.m_strTablesRoot = ""`（PRINT 后清空）| `serviceConnector.m_strTablesRoot = ""`（onSuccess/onError 清空）| ✅（不同对象）|
| 11 | 用户点 b_stopService（可选）| **不调用**（release 只 unregister）| ⚠️ |
| 12 | onDestroy 时 `serviceConnector.unregister()` | `releaseForPage → manager.release()` | ✅ |

### 5.4 应用代码层能判定的"嫌疑点"

下面 4 个点是**仅凭应用代码对比**就能识别的，不需要看 SDK 源码：

1. **`USB_CHECK_PRINTER_STATUS` 不预检**（§5.2.1）—— sample 持续探测 vs 我们冷启动直接打
2. **`StopService` 不调用**（§5.3 步骤 11）—— sample 显式 StopService，我们 release 只 unregister
3. **`serviceConnector.m_strTablesRoot` 在 PRINT 路径设值**（§5.2.2）—— sample 不设（保持 `""`），我们设成 `tablesRoot`
4. **`doService` 异步 vs 同步**（§3.6）—— sample 同步立即返回，我们异步 + 20s future.get

### 5.5 应用代码层无法判定的"嫌疑点"

下面这些点**需要 SDK 内部行为或 vendor 文档**才能判定，应用代码层看不出：

- `StartService()` 返回成功 ≠ SDK service 已 bind 完毕，期间 doService 行为如何
- `serviceConnector.m_strTablesRoot` 在 SDK 内部对 PRINT_PHOTOS 的影响
- SDK 卡死的真正原因（USB endpoint stall / native lib 卡死 / PrinterService 内部锁）

---

## 5. 已识别但**未深究**的潜在问题

1. **drawable bitmap vs file bitmap 的 decode 行为差异**
   - sample 的 `BitmapFactory.decodeStream(is, null, null)` 第三参数 `null` —— 不做 inSampleSize 缩放
   - 我们的 `BitmapFactory.decodeFile(bitmapPath)` 同款（`HiTiPrinterManager.java:397`），无差异
   - 但 sample 的 bitmap 来自 `res/drawable-hdpi/` 已经 1844×1240 物理像素；我们来自 TS canvas 1536×1024 → 与 PaperSize 期望的 1844×1240 不严格匹配。这**可能**导致 SDK 内部 scaling，但日志里 SDK 没报这个错。

2. **m_strTablesRoot 传递路径不一致**
   - sample: `operation.m_strTablesRoot` → `printerSetService` 内部再 `serviceConnector.m_strTablesRoot = m_strTablesRoot`
   - 我们: 直接 `serviceConnector.m_strTablesRoot = tablesRoot`
   - 实质等价，但 sample 在 `printerSetService` 内部还调了一次 `serviceConnector.doService(job)` 时 m_strTablesRoot 还没被清空，与 sample 主线一致。

3. **mJobId 起始值**
   - sample: `mJobId = 101`
   - 我们: `nextJobId = 101`
   - 一致。

4. **PRINT_PHOTO_TIMEOUT_SECONDS = 20s vs sample 的 USB transfer timeout**
   - Android USB 默认 control transfer timeout 是 ~5s（kernel 配置）；但 HiTi SDK 可能内部走 bulk transfer 或 vendor protocol，timeout 不可控
   - sample 没显式 timeout，假设 USB transfer timeout 内会返回错误或正常完成
   - 我们的 20s 兜底是合理的：实测 5~15s 出图，20s 留余量

---

## 6. 修复决策与时间线

| Commit | 内容 | 修复目标 |
|--------|------|---------|
| `510e621` | portrait 输入 letterbox + Java 端 90° 旋转 | 修复 #3（竖图被横打）|
| `ff601d5` | 临时回退 printPhoto doService 到 doServiceWithTimeout 兜底 | 修复 #4（doService 卡死）|

## 7. 下一步验证

提交 ff601d5 后，下次打印会按以下流程触发：

1. `printPhoto()` raw Thread 启动
2. 调 `doServiceWithTimeout(job, cb)` → `printExecutor.submit(() -> serviceConnector.doService(job))`
3. raw Thread 同步 `future.get(20s)` 等结果
4. **若 SDK 卡死**：20s 后 `TimeoutException` 触发 → `cb.onError("HiTi USB_PRINT_PHOTOS timed out after 20s ...")` → TS 端 await 拿到 `{ok: false, error}` → UI 立即显示失败
5. **若 SDK 正常**：5~15s 后正常返回 → `cb.onSuccess(null)` → TS 端拿到 `{ok: true, data: 'printed'}`

观察日志中的 `[doServiceWithTimeout: ... timed out after 20s]` 行判断：

- **触发** → SDK 本身卡死，需要联系 vendor
- **不触发 + 打印成功** → 8e19301 的 raw Thread 同步调用是问题所在
- **不触发 + 打印失败** → 问题在别处（bitmap / USB 状态 / SDK 参数），需要看新日志具体定位