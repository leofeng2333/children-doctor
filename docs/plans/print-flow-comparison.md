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