# Plan: 对焦辅助椭圆虚线框 + 底部引导文案

> 状态：方案（待实施）
> 关联视图：`CameraCaptureView.vue` → `CaptureSession.vue`（双预览由 native 渲染，本方案对 Vue 层零改动）
> 关联组件：`android/app/src/main/java/com/children/doctor/plugins/dualcamera/Camera2Controller.java`

---

## 1. 背景与目标

在拍摄预览页面中，为两个 native 预览画面各添加一个帮助用户对焦脸部的椭圆虚线框，并在两个预览区域下部添加一行文案（仅一行，跨两列）。

约束：

- 椭圆虚线框 + 文案**仅在预览开启时显示**，其它状态（拍照确认、final review、上传中、停预览）一概不出现。
- 不能影响现有功能（拍照、确认、重拍、final review、上传、HiTi 打印）。
- 不引入新依赖、新权限、新 Gradle 配置。
- Vue 端**零改动**。

---

## 2. 现状事实

### 2.1 双预览的渲染归属

两个预览画面由 Android **native 端**渲染，不由 WebView/DOM 控制：

- 文件：`android/app/src/main/java/com/children/doctor/plugins/dualcamera/Camera2Controller.java`
- 关键方法：`buildTextureViews(rootView, slotCount)` + `buildCameraSlotView(index, textureView, colMargin)`
- 视图层级：

  ```
  Activity DecorView
  └── android.R.id.content (rootView)
      ├── (WebView — Capacitor 渲染)
      └── container (LinearLayout HORIZONTAL, slotCount==2 时)
          ├── col (index=0)
          │   ├── label TextView("正视图")
          │   └── previewWrapper (FrameLayout)
          │       ├── textureView (TextureView — 摄像头预览)
          │       └── photoView   (ImageView — 拍照后定格图，默认 GONE)
          └── col (index=1)
              ├── label TextView("右侧视图")
              └── previewWrapper (FrameLayout)
                  ├── textureView
                  └── photoView
  ```

- `container` 通过 `rootView.addView(container)` 以追加方式加入，Z 序在 WebView 之上，会**盖住 Vue 层**。
- 列宽计算：`w = screenWidthPx * 0.415f`（双预览模式）
- 列高计算：`h = w * 4f / 3f`
- 标签字号：14sp，文字"正视图"/"右侧视图"（`CAMERA_LABELS` 静态常量）。

### 2.2 Vue 端的死代码

- `src/components/CaptureSession.vue` 的 `<style scoped>` 里有 `.native-preview-area`、`.native-preview-placeholder`，但 `<template>` 里**没有**对应的 DOM 节点。
- `CaptureSession.vue` 模板只渲染顶部文案、错误提示、底部按钮；**没有任何预览框 DOM**。
- 因此预览画面被 native `container` 盖住，Vue 无法在该位置叠 DOM。

### 2.3 拍照流程触发的 native 状态切换

| 触发 | 方法 | containerView 影响 |
|---|---|---|
| 调用 `startPreview()` | `DualCameraPlugin.startPreviewInternal` → `DualCameraManager.startPreview` → `Camera2Controller.startPreview` → `buildTextureViews` | `addView(container)`、photoView `GONE`、textureView `VISIBLE` |
| 调用 `capture()` | 拍照 → `displayPhotos()` | 把对应 col 的 `photoView` 切到 `VISIBLE`，textureView 保留在后层 |
| 调用 `resumePreviewFromPhotos()` | 重拍/确认后回调 | `photoView` 切回 `GONE`、textureView 切回 `VISIBLE` |
| 调用 `stopPreview()` | `DualCameraManager.stopPreview` → `Camera2Controller.stopPreview` → 移除 `containerView` | 整个 container 被 `rootView.removeView()` |

### 2.4 `getPreviewRects` 的存在情况

- `Camera2Controller.getPreviewRects()` 方法在内部已实现，可拿到每个 TextureView 的屏幕坐标 `Rect[]`。
- 但**未 wire 到 plugin 层**：JS 端没有任何 API 能拿到这个数组。
- 因此 Vue 端无法通过精确的屏幕坐标去叠 ellipse，只能走 native overlay 路线。

---

## 3. 设计选择

### 3.1 为什么必须 native 端实现（不是 Vue/CSS 端）

| 方案 | 可行性 |
|---|---|
| **A. native overlay（推荐）** | 在 `previewWrapper` 内部直接加一个 View 画 ellipse；在 `container` 底部加一行 TextView。位置由 native 计算保证 100% 准确。 |
| B. WebView 透明 + Vue CSS absolute 定位 | 需要：(a) Capacitor WebView 背景改透明 → 跨文件改动 +(b) `getPreviewRects` wire 出去 + (c) JS 端动态同步坐标链路。改动面大，且 WebView 改透明会影响其它页面。 |

结论：选 A。

### 3.2 可见性生命周期

| 阶段 | ellipse | 底部文案 |
|---|---|---|
| preview 开启、textureView AVAILABLE | **VISIBLE** | **VISIBLE** |
| `displayPhotos()` 后（pendingPhoto） | GONE | GONE |
| `resumePreviewFromPhotos()` 重拍/继续 | **VISIBLE** | **VISIBLE** |
| `stopPreview()` | 整个 container 被 removeView → 一并消失 | 同上 |
| finalReview / uploading | preview 已停止 → 一并消失 | 同上 |

实现方式：把 ellipse 与 caption 都作为 native View 的成员引用保存到 `Camera2Controller`，在 `displayPhotos` / `resumePreviewFromPhotos` / `stopPreview` 三个方法各加 1 行 `setVisibility(...)`。

### 3.3 容器布局改造：`container` 从 LinearLayout → FrameLayout

- 当前 `container` 是 `LinearLayout HORIZONTAL`（slotCount==2 时）。
- 若保持 LinearLayout 底部加 caption，caption 会把 cols 高度挤掉。
- **改为 FrameLayout**：cols 用 `layout_gravity=CENTER` 保持居中，caption 用 `layout_gravity=BOTTOM|CENTER_HORIZONTAL` 浮在底部。
- 单预览模式（slotCount==1）当前是 `LinearLayout VERTICAL`，本方案同样改为 `FrameLayout` 保证行为一致。

### 3.4 文案与样式（与你确认的选项对齐）

| 项 | 值 |
|---|---|
| 文案内容 | **"请将面部置于椭圆虚线框内"** |
| 文案颜色 | **#1A1A1A**（黑字） |
| 文案背景 | **透明**（仅靠 ellipse 与原背景区分） |
| 文案字号 | 14sp（与现有 label 同号；增大需复审） |
| ellipse 颜色 | **#FF9900**（品牌橙） |
| ellipse 风格 | strokeWidth=4px、`DashPathEffect(24,16)`、`STROKE` 模式 |
| ellipse 尺寸 | 占 previewWrapper 宽 80% × 高 60%，居中 |
| ellipse alpha | 0xFF（不透明） |

---

## 4. 实施步骤

### Step 1：新建 `FaceDetectionOverlay.java`

- 路径：`android/app/src/main/java/com/children/doctor/plugins/dualcamera/FaceDetectionOverlay.java`
- 类签名：`public class FaceDetectionOverlay extends View`
- 字段：

  | 字段 | 类型 | 用途 |
  |---|---|---|
  | `overlayPaint` | `Paint` | 画 ellipse 边线 |
  | `overlayRect` | `RectF` | 缓存当前 ellipse 矩形 |
  | `colorArgb` | `int` | 椭圆颜色（含 alpha），构造时传入 |
  | `widthRatio` | `float` | 占宽比例（默认 0.8f） |
  | `heightRatio` | `float` | 占高比例（默认 0.6f） |
  | `strokeWidthPx` | `float` | 线宽（默认 4f） |
  | `dashOnPx`、`dashOffPx` | `float` | 虚线节奏（默认 24f、16f） |

- 构造：`init()` 设置 `setClickable(false)`、`setFocusable(false)`、`WILL_NOT_DRAW` 默认；构造完成后 `init` 一次。
- `onSizeChanged(w, h)`：根据 `widthRatio` / `heightRatio` 算出 `overlayRect`，相对中心居中。
- `onDraw(Canvas)`：
  1. 设 `overlayPaint.color = colorArgb`、`style = STROKE`、`strokeWidth = strokeWidthPx`
  2. `setPathEffect(new DashPathEffect(new float[]{dashOnPx, dashOffPx}, 0))`
  3. `canvas.drawOval(overlayRect, overlayPaint)`
- 类注释：明确说明"仅装饰，不参与触摸链路"。

### Step 2：修改 `Camera2Controller.java`

涉及三处：`buildTextureViews`、`displayPhotos`、`resumePreviewFromPhotos`、`stopPreview`（兜底）。

#### 2.1 字段新增

```java
/** 每列的椭圆虚线框 overlay。索引对齐 slot (0/1)。 */
private android.view.View[] focusOverlays;
/** container 底部跨列文案 TextView，仅有一个（slotCount>=1 时都显示）。 */
private android.widget.TextView focusCaption;
```

#### 2.2 `buildTextureViews` 改造

- `container` 改为 `FrameLayout`（无论 slotCount 是 1 还是 2）：
  - 删掉 `setOrientation(...)` 与 `setGravity(CENTER)` 调用
  - `LayoutParams` 保持 `MATCH_PARENT × MATCH_PARENT`
- 列 `col` 的 `LinearLayout.LayoutParams` 改为 `FrameLayout.LayoutParams`，加 `gravity = Gravity.CENTER`
- 在每次 `container.addView(root)` 后，新增：

  ```java
  // 每列加 ellipse overlay（在 previewWrapper 内部）
  View focusOverlay = new FaceDetectionOverlay(context);
  previewWrapper.addView(focusOverlay,
      new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
  focusOverlays[i] = focusOverlay;
  ```

- `focusOverlays` 初始化在循环外（`new View[slotCount]`）。
- 最后加 caption：

  ```java
  focusCaption = new TextView(context);
  focusCaption.setText("请将面部置于椭圆虚线框内");
  focusCaption.setTextSize(14);
  focusCaption.setTextColor(0xFF1A1A1A);
  focusCaption.setBackgroundColor(0x00000000); // 透明
  focusCaption.setSingleLine(true);
  focusCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);
  FrameLayout.LayoutParams capParams =
      new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
  capParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
  capParams.bottomMargin = dpToPx(24);
  capParams.leftMargin = dpToPx(24);
  capParams.rightMargin = dpToPx(24);
  focusCaption.setLayoutParams(capParams);
  container.addView(focusCaption);
  ```

- 重要：`previewWrapper` 现在是 FrameLayout，**安全地把 overlay 作为第三个 child 加在 textureView/photoView 之上的最上层**。

#### 2.3 `displayPhotos` 改造

- 在现有 `displayPhotos(paths)` 方法最后（photoView 切换为 VISIBLE 之后）追加：

  ```java
  if (focusOverlays != null) {
      for (View v : focusOverlays) {
          if (v != null) v.setVisibility(View.GONE);
      }
  }
  if (focusCaption != null) focusCaption.setVisibility(View.GONE);
  ```

#### 2.4 `resumePreviewFromPhotos` 改造

- 在现有 `resumePreviewFromPhotos()` 方法中（photoView 切回 GONE 后）追加：

  ```java
  if (focusOverlays != null) {
      for (View v : focusOverlays) {
          if (v != null) v.setVisibility(View.VISIBLE);
      }
  }
  if (focusCaption != null) focusCaption.setVisibility(View.VISIBLE);
  ```

#### 2.5 `stopPreview` 兜底

- `stopPreview` 当前逻辑是 `rootView.removeView(containerView)`，container 整体被移除，ellipse/caption 一并消失 — **不需要新增代码**。
- `focusOverlays = null`、`focusCaption = null` 引用清理是好的习惯（避免内存泄漏），在 containerView = null 之后置空即可。

### 2.6 阀门点：内存卫生

- `stopPreview` / `shutdown` 时同步置 `focusOverlays = null` / `focusCaption = null`，与 `textureViews = null` / `photoImageViews = null` 放在同一段。
- `buildTextureViews` 入口先 `focusOverlays = new View[slotCount]`、`focusCaption = null`，避免上一帧残留。

---

## 修订说明（2026-08-20 二次核查发现）

二次对照源码逐项核查时，发现**方案本身有 3 个会影响视觉/现有功能的问题**，已逐项修复。

### 修订 A：FrameLayout 改造后两列不再由 LinearLayout 顺序排列

**问题**：最初方案把 `container` 改为 `FrameLayout`、两列都用 `layout_gravity = CENTER`。
改完后 FrameLayout 不会自动排布子 view，两列 `gravity=CENTER` 会**叠在同一坐标**（两摄像头画面完全重叠）。
这会让用户**只能看到第二个 camera 的画面**，相当于**现有功能回归**。

**修复**：在 `buildCameraSlotView` 返回的 `col` 上用区分的 `layout_gravity`：

```java
final FrameLayout.LayoutParams colParams =
        new FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT);
colParams.gravity = slotCount == 1
        ? Gravity.CENTER
        : (index == 0 ? Gravity.CENTER_VERTICAL | Gravity.START
                      : Gravity.CENTER_VERTICAL | Gravity.END);
colParams.setMargins(/* 保留原 marginStart/marginEnd 逻辑 */);
col.setLayoutParams(colParams);
```

单预览模式（slotCount==1）保持 `CENTER`；双预览模式区分 `START` / `END`。
marginStart / marginEnd 沿用现有 `colMargin` 逻辑（`buildCameraSlotView` line 232-235），列间间距不变。

### 修订 B：`displayPhotos` 的 ellipse GONE 时机

**问题**：原方案把 ellipse `GONE` 放在 `displayPhotos` 流程的"photoView 切到 VISIBLE 之后"。
但 `displaySinglePhoto` 内部 `photoView.post(() -> ... animatePhotoTransition(...))` 走 200ms 渐显。
**200ms 内 ellipse 仍可见 → 椭圆线会压在拍照结果上**（用户能看到"橙色椭圆虚线叠在照片上"）。

**修复**：GONE 改在 `displayPhotos` 入口的同步段，**不等** photoView 动画：

```java
public void displayPhotos(String[] photoPaths) {
    mainHandler.post(() -> {
        if (photoImageViews == null || photoPaths == null) return;

        // ✅ 入口同步隐藏 overlay（先于 photoView.post 渐显）
        if (focusOverlays != null) {
            for (View v : focusOverlays) if (v != null) v.setVisibility(View.GONE);
        }
        if (focusCaption != null) focusCaption.setVisibility(View.GONE);

        Log.d(TAG, "displayPhotos: " + photoImageViews.length + " slots");
        for (int i = 0; i < photoImageViews.length; i++) {
            if (photoImageViews[i] == null || photoPaths[i] == null) continue;
            displaySinglePhoto(i, photoPaths[i]);
        }
        Log.d(TAG, "displayPhotos done");
    });
}
```

视觉结果：用户确认拍照 → ellipse 立即消失 → photoView 200ms 渐显 → 干净。

### 修订 C：`resumePreviewFromPhotos` 的 ellipse 渐显

**问题**：原方案 ellipse 直接 `setVisibility(VISIBLE)`，无动画。
重拍瞬间：photoView 渐隐 150ms → textureView 渐显 150ms → ellipse 立即 VISIBLE。
视觉上会**短暂看到"黑屏上浮着一个橙色椭圆"**，然后 camera 画面填进去。

**修复**：ellipse 跟随 textureView 同步 150ms alpha 渐显：

```java
// 与 textureViews[i].animate().alpha(1f) 平级
if (focusOverlays != null && focusOverlays[i] != null) {
    focusOverlays[i].setAlpha(0f);
    focusOverlays[i].setVisibility(View.VISIBLE);
    focusOverlays[i].animate().alpha(1f).setDuration(150).start();
}
if (focusCaption != null) {
    focusCaption.setAlpha(0f);
    focusCaption.setVisibility(View.VISIBLE);
    focusCaption.animate().alpha(1f).setDuration(150).start();
}
```

视觉结果：重拍 → 画面 150ms 渐显同步带 ellipse 渐显 → 无违和。

### 修订 D：buildTextureViews 入口保护

在 `buildTextureViews` 开头加：

```java
focusOverlays = new View[slotCount];
focusCaption = null;
```

避免上次 preview 残留字段穿到新一次预览（虽然 stopPreview 已经清理，但双保险）。

---

## 5. 不影响现有功能的论证（含修订后的最终论证）

| 维度 | 是否影响 | 论证 |
|---|---|---|
| `Vue` 任何文件 | **零改动** | `CaptureSession.vue` / `CameraCaptureView.vue` / 路由 / 状态机 / Pinia store 都不动 |
| JS plugin API | **零改动** | `@PluginMethod` 列表不变；`startPreview` / `stopPreview` / `capture` 入参/出参不变 |
| 拍照主流程 | **零实质改动** | `displayPhotos` 入口同步 GONE overlay（早于 photoView 渐显 200ms），不阻塞拍照管线 |
| 触摸链路 | 不拦截 | `setClickable(false)`、`setFocusable(false)`、不挂 onTouchListener |
| Gradle / 依赖 / 权限 | **零改动** | 新文件在 `dualcamera` 包内，comping 已涵盖 |
| `DualCameraPlugin` / `DualCameraManager` | **零改动** | 所有改动收敛在 `Camera2Controller` 一个文件 |
| HiTi 打印管线 | **零改动** | 独立文件树 |
| 后端 / 分析 / 上传 | **零改动** | preview 是纯本地渲染 |
| **双预览画面布局**（修订 A） | **不重叠** | `col[0]` 用 `START` / `col[1]` 用 `END` 在 `FrameLayout` 内区分；marginStart / marginEnd 沿用原 `colMargin` 逻辑 |
| **拍照后 200ms 椭圆压在照片上**（修订 B） | **不发生** | ellipse GONE 在 `displayPhotos` 入口同步设置，早于 photoView 渐显开始 |
| **重拍时黑屏上先出椭圆**（修订 C） | **不发生** | ellipse 跟 textureView 同步 `alpha(0 → 1)` 150ms 渐显 |
| **预览复用时 overlay 字段残留**（修订 D） | **不发生** | `buildTextureViews` 入口重新 `new View[slotCount]` |

---

## 6. 验证计划

### 6.1 静态检查

```bash
pnpm type-check      # Vue 端零 TS 报错
pnpm lint:oxlint     # 不引入新 lint 错
pnpm build           # Vite 编译通过
```

### 6.2 Android 端（实施后用真机/Capacitor run）

1. `pnpm cap:sync`
2. 安装到设备，进入拍照页面
3. 观察首屏：
   - 两个预览画面各有一个**橙黄色虚线椭圆**
   - 底部一行**黑色文字 "请将面部置于椭圆虚线框内"**，透明底
4. 点击"咔嚓！"，倒计时，拍照：
   - ellipse 立即消失
   - 文案消失
   - 出现拍照确认 UI（Vue 端）
5. 点击"重新拍摄"：
   - ellipse 重新出现
   - 文案重新出现
6. 点击"下一步" 走完两轮 → finalReview：
   - 预览已停，ellipse/文案不会出现
7. finalReview → 开始分析 → 上传 → 跳转 question：均无 ellipse / 文案残留

### 6.3 兼容性回归

- 确认拍照后保存的 `frontCameraUrl` / `backCameraUrl` 与改动前一致。
- 确认 finalReview 阶段 2x2 网格四张图正确显示（最容易被吞）。
- 确认第二轮重拍时 `round = 2` 文案"合上小嘴巴..."出现在正确位置（来自 Vue 端，不受 native overlay 影响）。

---

## 7. 风险与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| 加 ellipse 后触摸事件被吞 | **中** | `setClickable(false)`、`setFocusable(false)`、不挂 listener |
| 拍照后 photoView 盖住 ellipse 但仍可见 | **低** | 显式 `setVisibility(GONE)` |
| FrameLayout 切换后列布局错位 | **低** | 用 `layout_gravity=CENTER` 保留居中 |
| 底部文案字号在低端机偏小 | **低** | 14sp 与现有 label 一致 |
| `displayPhotos` 与 overlay GONE 时序错乱 | **中** | 在 `displayPhotos` 内部 photoView 切 VISIBLE **之后**才置 ellipse 为 GONE |
| 字号 / dp 转 px 跨设备一致 | **低** | 沿用现有 `dpToPx()` API |
| WebView 内容被 native container 盖住（与本方案无关但需要确认） | **低** | 本方案目标就是 native overlay，不依赖 WebView DOM；文案的视觉位置在 native 容器底部，与 WebView 内容互不干扰 |

---

## 8. 改动量盘点

| 文件 | 行数预估 | 备注 |
|---|---|---|
| `FaceDetectionOverlay.java`（新） | ~70 行 | 一个 View，包含字段、init、onSizeChanged、onDraw |
| `Camera2Controller.java`（改） | ~40 行新增 | 字段 2 个、`buildTextureViews` 改 ~20 行、`displayPhotos` 1 行、`resumePreviewFromPhotos` 1 行 |
| **合计** | ~110 行 | 1 新文件 + 1 修改文件 |

---

## 9. 后续可扩展项（非本方案范围）

- 用 Face Detection API（ML Kit / Face++）自动定位人脸，动态调整 ellipse 位置/大小。
- 椭圆支持人手点击"重置对焦"。
- 顶部/底部文案 i18n（目前 hard-coded 中文）。
- 文案动态化（不同 round 显示不同引导语）。

---

## 10. 文档元信息

| 项 | 值 |
|---|---|
| 创建时间 | 2026-08-20 |
| 适用版本 | children-doctor >= 1.0.7 |
| 涉及 PR / 任务 | （待定） |
| 评审人 | （待定） |
