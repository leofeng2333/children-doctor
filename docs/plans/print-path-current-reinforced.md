# 打印问题诊断 - path-current（强化版）

> **路径定位**：基于现有打印逻辑（HEAD = c6096b8）的多模式诊断能力，叠加"防御性增强"
> 的纯增量改进。**不破坏现有 printMode 4 个 mode 的能力**，只补强每条路径的鲁棒性。

---

## 1. 当前已知问题（HEAD 实测）

| 现象 | 复现条件 | 根因 |
|------|---------|------|
| 冷启动 25s 卡死 | 用户在打印页面第一次按 print 按钮 | `StartService()` 返回 0 后立即调 `USB_PRINT_PHOTOS`，USB control transfer 通道未热身，SDK 内部 stall |
| portrait 输入被横打 | fitImageToPaper 走 letterbox 输出 portrait bitmap → SDK | SDK 不 rotate bitmap，按 landscape paper 比例 letterbox 后留白 |
| printMode 误用 | 生产路径没有 printMode，传 `'current'` 走 letterbox portrait | letterbox portrait 给 SDK → 25s 卡死 |
| 无失败重试 | 一次失败就放弃，UI 立即报错 | 冷启动失败和 SDK 真挂混在一起，前者本可重试一次 |

---

## 2. path-current 的目标

保留 4 个 printMode 的多模式诊断，叠加 3 层强化：

### 2.1 强制 paperType=2 fast path（1536×1024 → 1844×1240）

**变更点**：`buildPhotoAttrSample` 检测 paperType=2 时，如果输入 bitmap 不是 1844×1240，强制
做一次 center-crop → 1844×1240 物理像素。命中 SDK 注释里 PAPER_SIZE_6X4_PHOTO 期望的
"fast path"。

理由：
- sample bitmap 已经 1844×1240（physical pixels）
- 实测 1536×1024 → SDK 内部再 scaling 时偶发 stall（[print_20260825_082743_517.log](../print_logs/print_20260825_082743_517.log)）
- 翻到 1844×1240 后跨 SDK fast path，doService 内部不再做 bitmap scaling

### 2.2 冷启动 readiness gate（StartService 后等待 ClockTask 跑 2 个周期）

**变更点**：在 `printPhoto()` 入口增加 `awaitWarmup()` —— 等 ClockTask 至少跑 2 次（即距
StartService 至少 6 秒）再调 doService。

理由：
- sampleAPK 持续 keepalive，USB 通道永远热
- children-doctor 单次按 print → StartService → 立即 USB Print，冷启动失败
- 6 秒等待 = ClockTask 跑 2 次（initialDelay=3s + interval=3s），期间 USB 通道已被 keepalive

**代价**：每次冷启动多等 ~6s。用户感知：进入页面 → 选图 → 按 print → 6s 等待 → 出图。
热打印（同一会话内第二次 print）：ClockTask 已经在跑，warmup gate 立即通过，**无额外等待**。

### 2.3 失败重试 1 次（区分冷启动 vs SDK 真挂）

**变更点**：`printPhoto()` 捕获失败后，如果是 doService timeout 而非业务错误，自动重试 1
次（不重新 StartService，因为 StartService 仍是 0x0 success；只是重发 USB_PRINT_PHOTOS）。

理由：
- 冷启动 timeout 多半是首次 USB transfer 没热身；第二次发同样数据通常成功
- SDK 真挂（连续 2 次都 timeout）才算真失败
- 重试间隔 2s（一个 ClockTask 周期），让 USB 通道有更长的 keepalive

---

## 3. 不变的部分（兼容性保证）

- 4 个 printMode 行为不变（current / cover-fit / portrait-rotate / portrait-rotate-normalize）
- Capacitor bridge API 不变（`HiTiPrinter.printPhoto({ base64, paperType, bitmapProcessMode })`）
- 前端 PrintTestView.vue / ScanSubscription.vue 不用改
- v1.0.15-print-stable tag 的"doService 同步 + ClockTask keepalive"基础不变

---

## 4. 与 sampleAPK / v1.0.15-print-stable 的关系

| 维度 | v1.0.15-print-stable | sampleAPK | path-current（本分支） |
|------|---------------------|-----------|---------------------|
| 同步 doService | ✅ | ✅ | ✅ |
| ClockTask keepalive | ✅ | ✅ | ✅ |
| doServiceWithTimeout 兜底 | ❌（回退）| ❌ | ✅ |
| printMode 多模式 | ❌ | ❌ | ✅（保留）|
| 1844×1240 fast path | ❌（raw bitmap）| ✅（drawable 已是）| ✅（强制 scale）|
| 冷启动 readiness gate | ❌ | ❌（不需要，ClockTask 一直在）| ✅ |
| 失败重试 | ❌ | ❌ | ✅ |

path-current 是在 v1.0.15-print-stable 的基础上"加固"，而不是"重写"。

---

## 5. 文件改动清单

1. `android/app/src/main/java/com/children/doctor/plugins/hitiprinter/HiTiPrinterManager.java`
   - 新增 `warmupGate()`：await ClockTask 至少跑 2 次
   - 新增 `retryPrintOnTimeout()`：doService timeout 自动重试 1 次
   - `buildPhotoAttrSample` 加入 paperType=2 强制 1844×1240 fast path
   - `printPhoto()` 入口先调 `warmupGate()`，失败回调走 `retryPrintOnTimeout()`

2. `android/app/src/main/java/com/children/doctor/plugins/hitiprinter/HiTiPrinterPlugin.java`
   - 无改动（bridge API 不变）

3. `src/utils/print.ts` / `src/views/PrintTestView.vue`
   - 无改动（前端不感知 warmup gate 和 retry）

---

## 6. 验证步骤

冷启动路径：
1. adb uninstall com.children.doctor
2. 安装新 APK
3. 进入 PrintTestView
4. 选内置 analysis-success.png
5. 按 "1. current"
6. 期望：~6s warmup 等待 → 1s~5s 打印 → 出图
7. 按 "2. cover-fit"
8. 期望：~1s warmup gate 通过（hot）→ 1s~5s 打印 → 出图
9. 按 "3. portrait-rotate"
10. 期望：~1s warmup gate 通过（hot）→ 1s~5s 打印 → 出图（rotated landscape）

热打印路径（同一会话内连续 print）：
1. 打印第 1 张（warmup gate 通过）→ 出图
2. 打印第 2 张（warmup gate 立即通过）→ 出图
3. 期望：第 2 次打印与第 1 次间隔 < 6s（warmup gate 不阻塞）

失败重试路径：
1. 拔掉 USB hub 上的 HiTi 打印机
2. 进入打印页面
3. 按 print
4. 期望：~6s warmup + ~20s doService timeout → 重试 1 次 → ~20s 重试 timeout → UI 报错
5. 总耗时：~46s（不是无超时挂死）

---

## 7. 回滚

如果 path-current 在实测上引入新 bug：
```bash
git checkout v1.0.15-print-stable -- android/app/src/main/java/com/children/doctor/plugins/hitiprinter/
git commit -m "revert: print-path-current 引入新问题，回滚 HiTiPrinterManager"
```

---

## 8. 与其它路径的取舍

| 维度 | path-current | path-sampleapk | path-tag-baseline |
|------|-------------|----------------|-------------------|
| 改动量 | 中（增量加固）| 大（重写到 sampleAPK 形态）| 小（只补 fast path + retry）|
| 风险 | 中（新增 retry 路径）| 高（重写可能丢多模式）| 低（接近 tag baseline）|
| 性能 | 冷启动 +6s，热打印无影响 | 持续 keepalive 无等待 | 冷启动 +6s，热打印无影响 |
| 多模式诊断 | ✅ 保留 | ❌ 移除（精简）| ✅ 保留 |
| 适用场景 | 想要"加固但保留诊断能力" | 信任 sampleAPK 形态可以不要多模式 | 信任 tag baseline 只想小改 |

> 详见 `print-paths-comparison.md` 三路径总对比。