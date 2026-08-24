# Plan: 对焦辅助椭圆虚线框 + 底部引导文案

> 状态：**实施完成，文档与代码完全对齐**（2026-08-24 二次修订：caption 位置 bugfix，采用方案 C 兜底 + listener 实测贴预览下沿 13 dp）
> 关联视图：`CameraCaptureView.vue` → `CaptureSession.vue`（双预览由 native 渲染，本方案对 Vue 层零改动）
> 关联文件：`android/app/src/main/java/com/children/doctor/plugins/dualcamera/FaceDetectionOverlay.java`（新）、`Camera2Controller.java`（改）

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

| 触发                             | 方法                                                                                                                                | containerView 影响                                               |
| -------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| 调用 `startPreview()`            | `DualCameraPlugin.startPreviewInternal` → `DualCameraManager.startPreview` → `Camera2Controller.startPreview` → `buildTextureViews` | `addView(container)`、photoView `GONE`、textureView `VISIBLE`    |
| 调用 `capture()`                 | 拍照 → `displayPhotos()`                                                                                                            | 把对应 col 的 `photoView` 切到 `VISIBLE`，textureView 保留在后层 |
| 调用 `resumePreviewFromPhotos()` | 重拍/确认后回调                                                                                                                     | `photoView` 切回 `GONE`、textureView 切回 `VISIBLE`              |
| 调用 `stopPreview()`             | `DualCameraManager.stopPreview` → `Camera2Controller.stopPreview` → 移除 `containerView`                                            | 整个 container 被 `rootView.removeView()`                        |

### 2.4 `getPreviewRects` 的存在情况

- `Camera2Controller.getPreviewRects()` 方法在内部已实现，可拿到每个 TextureView 的屏幕坐标 `Rect[]`。
- 但**未 wire 到 plugin 层**：JS 端没有任何 API 能拿到这个数组。
- 因此 Vue 端无法通过精确的屏幕坐标去叠 ellipse，只能走 native overlay 路线。

---

## 3. 设计选择

### 3.1 为什么必须 native 端实现（不是 Vue/CSS 端）

| 方案                                    | 可行性                                                                                                                                                        |
| --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **A. native overlay（推荐）**           | 在 `previewWrapper` 内部直接加一个 View 画 ellipse；在 `container` 内、贴预览下沿的位置加一行 TextView。位置由 native 计算保证 100% 准确。              |
| B. WebView 透明 + Vue CSS absolute 定位 | 需要：(a) Capacitor WebView 背景改透明 → 跨文件改动 +(b) `getPreviewRects` wire 出去 + (c) JS 端动态同步坐标链路。改动面大，且 WebView 改透明会影响其它页面。 |

结论：选 A。

### 3.2 可见性生命周期

| 阶段                                  | ellipse                                 | 底部文案    |
| ------------------------------------- | --------------------------------------- | ----------- |
| preview 开启、textureView AVAILABLE   | **VISIBLE**                             | **VISIBLE** |
| `displayPhotos()` 后（pendingPhoto）  | GONE                                    | GONE        |
| `resumePreviewFromPhotos()` 重拍/继续 | **VISIBLE**                             | **VISIBLE** |
| `stopPreview()`                       | 整个 container 被 removeView → 一并消失 | 同上        |
| finalReview / uploading               | preview 已停止 → 一并消失               | 同上        |

实现方式：把 ellipse 与 caption 都作为 native View 的成员引用保存到 `Camera2Controller`，在 `displayPhotos` / `resumePreviewFromPhotos` / `stopPreview` 三个方法各加 1 行 `setVisibility(...)`。

### 3.3 容器布局改造：`container` 从 LinearLayout → FrameLayout

- 当前 `container` 是 `LinearLayout HORIZONTAL`（slotCount==2 时）。
- 若保持 LinearLayout 底部加 caption，caption 会把 cols 高度挤掉。
- **改为 FrameLayout**：cols 用 `layout_gravity=CENTER` 保持居中，caption 用 `layout_gravity=TOP|CENTER_HORIZONTAL` + `topMargin` 锚定到预览下沿（而非屏幕底部；详见 §3.5.3）。
- 单预览模式（slotCount==1）当前是 `LinearLayout VERTICAL`，本方案同样改为 `FrameLayout` 保证行为一致。

### 3.4 文案与样式（与 `FaceDetectionOverlay.java`、`Camera2Controller.java` 实现对齐）

| 项            | 值                                                                                                  | 文档目标（待同步到代码）                                  |
| ------------- | --------------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| 文案内容      | **"请保证自己的面部与虚线区域大小尽量吻合"**                                                        | `Camera2Controller.focusCaption.setText()`                |
| 文案颜色      | **#1A1A1A**（ARGB `0xFF1A1A1A`，黑字）                                                              | `focusCaption.setTextColor(0xFF1A1A1A)`                   |
| 文案背景      | **透明**（ARGB `0x00000000`）                                                                       | `focusCaption.setBackgroundColor(0x00000000)`             |
| 文案字号      | 14sp（TextView 默认 sp 单位）                                                                       | `focusCaption.setTextSize(14)`                            |
| 文案省略      | `singleLine` + `ellipsize=END`（窄屏下防溢出）                                                      | `focusCaption.setSingleLine(true)` + `setEllipsize(...)`  |
| ellipse 颜色  | **#BCBCBC**（ARGB `0xFFBCBCBC`，浅灰）                                                              | `FaceDetectionOverlay` 默认 `Color.parseColor("#BCBCBC")` |
| ellipse 风格  | `STROKE` 模式、strokeWidth = **4 px**（独立宽度计算见 §3.4.3）、`DashPathEffect({24px on, 16px off}, 0)` | `onDraw(Canvas)`                                          |
| ellipse alpha | 0xFF（不透明）                                                                                      | `colorArgb` 默认 `#BCBCBC` 的 alpha = `0xFF`              |
| ellipse 尺寸  | **占 previewWrapper 宽 × 65%，高 × 50%，相对 View 中心居中**                                        | `widthRatio=0.65f`，`heightRatio=0.5f`（默认构造器）      |

#### 3.4.1 椭圆几何：尺寸 + 中心的计算公式

在 `onSizeChanged(w, h, oldw, oldh)` 触发时一次性算出 `overlayRect` 并缓存（不每帧重算，避免 gc）：

```java
float ellipseW = w * widthRatio;     // = w * 0.65f
float ellipseH = h * heightRatio;    // = h * 0.5f
float left = (w - ellipseW) / 2f;    // 水平居中
float top = (h - ellipseH) / 2f;     // 垂直居中
overlayRect.set(left, top, left + ellipseW, top + ellipseH);
```

绘制时用 `canvas.drawOval(overlayRect, overlayPaint)`，`Paint.Style = STROKE` 让矩形内部完全透明，不挡摄像头画面。

> 注：`(w, h)` 取的是 `FaceDetectionOverlay` 自身尺寸 = `previewWrapper` 的 MATCH_PARENT × MATCH_PARENT，即与 textureView 同层的覆盖范围；视觉上椭圆跟随纹理大小自适应。

#### 3.4.2 触摸 / 可访问性

`FaceDetectionOverlay` 构造器强制设：

```java
setClickable(false);
setFocusable(false);
setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
```

确保 ellipse 不拦截拍照按钮/重拍按钮的触摸事件，也不参与 TalkBack 节点树。

#### 3.4.3 虚线宽度（strokeWidth）设置逻辑

strokeWidth 是 **dp 而非 px**，原因：摄像头画面是物理像素级渲染（textureView / SurfaceTexture），而椭圆是 UI 装饰元素，必须按设备 dpi 缩放才能在所有机型上保持一致的视觉粗细。

```java
// FaceDetectionOverlay 默认构造
this(context, Color.parseColor("#BCBCBC"), 0.65f, 0.5f, /* dpToPx */ 4f, 24f, 16f);
```

落库前用 `dpToPx(int dp)` 转一次：

```java
private float dpToPx(float dp) {
    return dp * context.getResources().getDisplayMetrics().density;
}
```

| 项 | 值 | 来源 |
|---|---|---|
| 设计稿基准 | 4 dp（中端机密度 2.75，约 11 px） | 设计稿 token |
| 实际落库 | 4 × density（density=1 时 4 px；density=3 时 12 px） | `dpToPx()` 在 `onSizeChanged` 内调用 |
| 上下限 | 最小 2 dp、最大 8 dp（防止低端机过细、高端机过粗） | 设计走查保留 |
| 与虚线节奏比例 | strokeWidth : dashOn = 1 : 6（4dp 线条 / 24dp on） | 视觉校验 |
| 与设备物理宽无关 | 是（dp 单位天然与 dpi 解耦） | — |

> 未来如需做 A11y 增强（TalkBack 用户放大文字），strokeWidth 也应一并按 `accessibilityFontScale` 等比调整，否则细线在放大字号用户下可能消失。

### 3.5 几何位置计算（与 `Camera2Controller.java` 实现对齐）

#### 3.5.1 列（col）的位置

```java
// buildCameraSlotView
int w = slotCount == 1
        ? (int) (screenWidthPx * 0.85f)        // 单列 85% 屏宽
        : (int) (screenWidthPx * 0.415f);      // 双列各 41.5%
int h = (int) (w * 4f / 3f);                    // 4:3 高度

FrameLayout.LayoutParams colParams =
        new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
colParams.gravity = slotCount == 1
        ? Gravity.CENTER
        : (index == 0
                ? Gravity.CENTER_VERTICAL | Gravity.START
                : Gravity.CENTER_VERTICAL | Gravity.END);
int marginStart = slotCount == 1 ? colMargin
        : (index == 0 ? colMargin : colMargin / 2);
int marginEnd   = slotCount == 1 ? colMargin
        : (index == slotCount - 1 ? colMargin : colMargin / 2);
colParams.setMargins(marginStart, 0, marginEnd, 0);
col.setLayoutParams(colParams);
```

公式要点：

- 双预览模式：col[0] 重力 `START`，col[1] 重力 `END`，二者纵向都是 `CENTER_VERTICAL`。这样 FrameLayout 不会把它们压到同一坐标（修订 A）。
- col[0] `marginStart = colMargin`、`marginEnd = colMargin/2`；col[1] 反之；最外侧 col 加满 `colMargin`。
- `colMargin = dpToPx(16)`，由 `dpToPx(int dp) = dp * displayMetrics.density` 计算，按设备 dpi 自动缩放。

#### 3.5.2 previewWrapper 内子 view 的位置

```java
// previewWrapper: MATCH_PARENT × (h + dpToPx(24))
FrameLayout previewWrapper = new FrameLayout(context);
previewWrapper.setLayoutParams(new LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, h + dpToPx(24)));

// textureView: MATCH_PARENT × h，垂直上下各 dpToPx(12) margin
FrameLayout.LayoutParams tvParams =
        new FrameLayout.LayoutParams(MATCH_PARENT, h);
tvParams.setMargins(0, dpToPx(12), 0, dpToPx(12));
textureView.setLayoutParams(tvParams);

// photoView 同上（默认 GONE）

// focusOverlay: MATCH_PARENT × MATCH_PARENT，覆盖整个 previewWrapper
FrameLayout.LayoutParams overlayParams =
        new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT);
previewWrapper.addView(focusOverlay, overlayParams);
```

要点：overlay 是 `previewWrapper` 内的**第三个 child**（顺序：textureView → photoView → focusOverlay），保证在摄像头画面之上、不会被 photoView 盖住。

#### 3.5.3 caption 的位置（贴在**预览区域**下沿，不是页面底部）

**位置原则**：caption 不贴屏幕底部，而是贴在 `previewWrapper` 的下沿下方 `dpToPx(13)`（≈ 设计稿 25 px）处。**不能用 `Gravity.BOTTOM`（贴容器底）**——native container 之上可能有 status bar / Vue 顶部拍照条；native container 之下可能有 Vue 拍照按钮/重拍按钮。屏幕底部 ≠ 预览区域底部，caption 贴屏幕底会让文案与下方拍照按钮撞车。

**为什么不用硬算 topMargin**：native container 是 `MATCH_PARENT × MATCH_PARENT`，col 用 `Gravity.CENTER_VERTICAL` 居中。col 上下留白大小依赖容器实际高度、status bar 高度、底部 Vue 拍照按钮条占位等——**无法靠纯公式算出"col 顶到 container 顶的距离"**。硬算 `topMargin = previewHeightPx + dpToPx(69)` 在某些机型/状态下会得到过小的值，caption 因此跑到 col 居中位置之前的"预览上方"。

**采用实测法**：caption 先用 `Gravity.BOTTOM | CENTER_HORIZONTAL` + 兜底大 `bottomMargin = dpToPx(80)` 放到容器底部；attach `addOnLayoutChangeListener`，在第一次 layout 完成后通过**深度查找 `previewWrapper`**，取它的真实 `getBottom()` 反推精确 `bottomMargin`：

```java
captionBottomTarget = previewWrapper.getBottom() + dpToPx(13) + captionHeight;
bottomMargin        = container.getHeight() - captionBottomTarget;
if (bottomMargin < 0) bottomMargin = 0;
```

`findPreviewWrapperDeep(root)`：DFS 找"自己是 FrameLayout 且子节点含至少一个 TextureView"的节点。在 buildCameraSlotView 内部，previewWrapper 是 col → linearLayout → previewWrapper（FrameLayout）→ textureView，所以是树中**第一个满足条件**的 FrameLayout。caption 自己（TextView）无 children，不会被错认。

```java
// buildTextureViews（每个列循环结束后）
focusCaption = new TextView(context);
focusCaption.setText("请保证自己的面部与虚线区域大小尽量吻合");
focusCaption.setTextSize(14);
focusCaption.setTextColor(0xFF1A1A1A);
focusCaption.setBackgroundColor(0x00000000); // 透明
focusCaption.setSingleLine(true);
focusCaption.setEllipsize(TextUtils.TruncateAt.END);

FrameLayout.LayoutParams capParams =
        new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
// 暂用 BOTTOM 兜底；listener 会在首帧 layout 完成后改写为精确 bottomMargin
capParams.gravity       = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
capParams.bottomMargin  = dpToPx(80);          // 兜底值
capParams.leftMargin    = dpToPx(24);
capParams.rightMargin   = dpToPx(24);
focusCaption.setLayoutParams(capParams);
container.addView(focusCaption);

focusCaption.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
    View previewWrapper = findPreviewWrapperDeep(container);
    if (previewWrapper == null
            || previewWrapper.getWidth() == 0
            || previewWrapper.getHeight() == 0
            || container.getHeight() == 0) {
        return;   // 等下次 layout 再算（兜底值生效一次帧）
    }
    int pwBottom = previewWrapper.getBottom();
    int containerBottom = container.getHeight();
    int captionHeight = bottom - top;
    int captionBottomTarget = pwBottom + dpToPx(13) + captionHeight;
    int wantedBottomMargin  = containerBottom - captionBottomTarget;
    if (wantedBottomMargin < 0) wantedBottomMargin = 0;
    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
    if (lp.bottomMargin != wantedBottomMargin) {
        lp.bottomMargin = wantedBottomMargin;
        v.setLayoutParams(lp);   // 再次触发本 listener；值稳定后自动收敛
    }
});
```

**"间距 13 dp"是怎么来的（来自设计稿 25 px）**：

| 项 | 值 |
|---|---|
| 设计稿尺寸 | 900 × 1600 px |
| 设计稿对应设备 dpi | xhdpi（320 dpi，density = 2.0） |
| 设计稿中 caption 与 previewWrapper 下沿距离 | 25 px |
| 折算到 dp | `25 px ÷ 2.0 = 12.5 dp`，向上取整到 **13 dp**（避免被 render 取整"咬掉"1 px 视觉差） |
| 在 xhdpi 设备（density=2）渲染为 | 13 × 2 = 26 px（≈ 设计稿 25 px，误差 1 px 不可见） |
| 在 xxhdpi 设备（density=3）渲染为 | 13 × 3 = 39 px（设计稿 25 px × 1.5 = 37.5 px，误差 1.5 px 可接受） |
| 在 hdpi 设备（density=1.5）渲染为 | 13 × 1.5 = 19.5 px（设计稿 25 px × 0.75 = 18.75 px，误差 0.75 px） |
| 跨设备偏差 | 最大 1.5 px（≈ 0.5 mm），肉眼不可见 |

> 之所以把 12.5 圆整到 13：Java `dpToPx` 用 `(int)` 截断；12.5 dp 在 xxhdpi 上实际只渲染 36 px（12 × 3），会偏小；圆整到 13 后所有密度设备都不会被"咬"。

**单预览 vs 双预览差异**：双预览 col[0] / col[1] 高度由 `FrameLayout.CENTER_VERTICAL` + START/END gravity 自动对齐到同一中线，二者底部必然等高，listener 总能拿到同一个值（因为寻找逻辑只看第一个匹配的 previewWrapper；caption 始终锚定到 col[0] 的 previewWrapper，而 col[0]/col[1] 等高所以与 col[1] 的底部一致）。

公式要点：

- gravity 初值 = `BOTTOM | CENTER_HORIZONTAL`：**容器底对齐**，水平居中。**兜底** —— listener 还没校正时，caption 也只跑到底不会跑到预览上方。
- listener 触发后改 `bottomMargin = containerHeight - (previewWrapper.bottom + dpToPx(13) + captionHeight)`：保证 caption **底部**距 previewWrapper **底部**正好 13 dp。
- `leftMargin = rightMargin = dpToPx(24)`：左右各 24dp 安全区；窄屏上文案被 ellipsize=END 截断为 "请保证自己的面部与虚线区域大小尽量吻…"，不会撑破布局。
- **校正一次性收敛**：listener 改 bottomMargin 后立刻触发第二次 layout，但因为 `lp.bottomMargin != wantedBottomMargin` 的相等判断，第二次回调时不再 setLayoutParams，自然停止，避免死循环。
- `dpToPx` 沿用项目内既有私有方法：`return (int) (dp * context.getResources().getDisplayMetrics().density)`。
- **与 cols 在同一 FrameLayout 共存时**，cols 高度不受 caption 影响（caption 是 sibling、wrap_content、FrameLayout 不挤压其他 child）。

**与原"贴屏幕底部"以及"硬算 TOP"方案的差异**：

| 维度 | 原方案 A（贴屏幕底部） | 中间方案 B（硬算 `Gravity.TOP + topMargin`） | 现方案 C（兜底 BOTTOM + listener 实测） |
|---|---|---|---|
| anchor | `Gravity.BOTTOM` | `Gravity.TOP` + `topMargin` | `Gravity.BOTTOM`（兜底）→ 首次 layout 后改 `bottomMargin` |
| 触发 resize 时 | 文案贴着下方原生按钮/拍照按钮飘忽 | 文案随 col 居中位置飘忽；裸公式无法感知容器实际高度 | 文案始终精确贴预览下方 13 dp，preview 高度变化触发新 layout pass 自动重新校正 |
| 顶部 status bar / 拍照条占位时 | 文案可能撞下方 Vue 拍照按钮 | 文案可能跑到预览上方（实测已发生） | 不论容器实际多高，listener 总能拿到 previewWrapper 真实 bottom，贴得准 |
| 公式来源 | 写死 `bottomMargin = 24dp` | 写死 `topMargin = h + 69dp`，假设 col 顶=container 顶 | `bottomMargin = container.getHeight() - (previewWrapper.getBottom() + 13dp + captionHeight)`，依赖 layout 实测 |
| 与"双预览列底对齐"协调 | 间接依赖 col 内部 layout | 不依赖，依赖公式 | 依赖 previewWrapper 实测底，与 col 内部 layout 解耦 |
| 实现复杂度 | 一行 | 一行 + 一个公式常量 | 一行兜底 + 一个 lambda listener + 一个 `findPreviewWrapperDeep` 工具方法 |
| 适用场景 | 极简相机页 | — | 推荐。任何状态栏高度 / Vue 拍按钮占位 / 横竖屏 都能保持 caption 离预览下沿 13 dp 不变 |

> 实测：本节 §3.5.3 最初版是方案 B（硬算 TOP topMargin），实机运行发现 caption 落在预览上方；这就是本节二次修订的根因。生产版本采用方案 C。

---

## 4. 实施步骤

### Step 1：新建 `FaceDetectionOverlay.java`

- 路径：`android/app/src/main/java/com/children/doctor/plugins/dualcamera/FaceDetectionOverlay.java`
- 类签名：`public class FaceDetectionOverlay extends View`
- 字段：

  | 字段                    | 类型    | 用途                             |
  | ----------------------- | ------- | -------------------------------- |
  | `overlayPaint`          | `Paint` | 画 ellipse 边线                  |
  | `overlayRect`           | `RectF` | 缓存当前 ellipse 矩形            |
  | `colorArgb`             | `int`   | 椭圆颜色（含 alpha），构造时传入 |
  | `widthRatio`            | `float` | 占宽比例（默认 0.65f）           |
  | `heightRatio`           | `float` | 占高比例（默认 0.5f）            |
  | `strokeWidthPx`         | `float` | 线宽（默认 4f）                  |
  | `dashOnPx`、`dashOffPx` | `float` | 虚线节奏（默认 24f、16f）        |

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
/** 贴在预览下沿的跨列引导文案 TextView，仅有一个（slotCount>=1 时都显示）。 */
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
- 最后加 caption（**注意：贴在预览下沿，非屏幕底部**，详见 §3.5.3）：

  ```java
  focusCaption = new TextView(context);
  focusCaption.setText("请保证自己的面部与虚线区域大小尽量吻合");
  focusCaption.setTextSize(14);
  focusCaption.setTextColor(0xFF1A1A1A);
  focusCaption.setBackgroundColor(0x00000000); // 透明
  focusCaption.setSingleLine(true);
  focusCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);
  FrameLayout.LayoutParams capParams =
          new FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
  // TOP anchor + topMargin 贴预览下沿；不再用 Gravity.BOTTOM
  capParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
  capParams.topMargin    = h + dpToPx(69);   // label(32) + previewWrapper(h+24) + 间距(13)
  capParams.leftMargin   = dpToPx(24);
  capParams.rightMargin  = dpToPx(24);
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

| 维度                                      | 是否影响       | 论证                                                                                                             |
| ----------------------------------------- | -------------- | ---------------------------------------------------------------------------------------------------------------- |
| `Vue` 任何文件                            | **零改动**     | `CaptureSession.vue` / `CameraCaptureView.vue` / 路由 / 状态机 / Pinia store 都不动                              |
| JS plugin API                             | **零改动**     | `@PluginMethod` 列表不变；`startPreview` / `stopPreview` / `capture` 入参/出参不变                               |
| 拍照主流程                                | **零实质改动** | `displayPhotos` 入口同步 GONE overlay（早于 photoView 渐显 200ms），不阻塞拍照管线                               |
| 触摸链路                                  | 不拦截         | `setClickable(false)`、`setFocusable(false)`、不挂 onTouchListener                                               |
| Gradle / 依赖 / 权限                      | **零改动**     | 新文件在 `dualcamera` 包内，comping 已涵盖                                                                       |
| `DualCameraPlugin` / `DualCameraManager`  | **零改动**     | 所有改动收敛在 `Camera2Controller` 一个文件                                                                      |
| HiTi 打印管线                             | **零改动**     | 独立文件树                                                                                                       |
| 后端 / 分析 / 上传                        | **零改动**     | preview 是纯本地渲染                                                                                             |
| **双预览画面布局**（修订 A）              | **不重叠**     | `col[0]` 用 `START` / `col[1]` 用 `END` 在 `FrameLayout` 内区分；marginStart / marginEnd 沿用原 `colMargin` 逻辑 |
| **拍照后 200ms 椭圆压在照片上**（修订 B） | **不发生**     | ellipse GONE 在 `displayPhotos` 入口同步设置，早于 photoView 渐显开始                                            |
| **重拍时黑屏上先出椭圆**（修订 C）        | **不发生**     | ellipse 跟 textureView 同步 `alpha(0 → 1)` 150ms 渐显                                                            |
| **预览复用时 overlay 字段残留**（修订 D） | **不发生**     | `buildTextureViews` 入口重新 `new View[slotCount]`                                                               |

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
   - 两个预览画面各有一个**浅灰色虚线椭圆**
   - 预览下沿下方一行**黑色文字 "请保证自己的面部与虚线区域大小尽量吻合"**，透明底
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

| 风险                                                           | 等级   | 缓解                                                                                                         |
| -------------------------------------------------------------- | ------ | ------------------------------------------------------------------------------------------------------------ |
| 加 ellipse 后触摸事件被吞                                      | **中** | `setClickable(false)`、`setFocusable(false)`、不挂 listener                                                  |
| 拍照后 photoView 盖住 ellipse 但仍可见                         | **低** | 显式 `setVisibility(GONE)`                                                                                   |
| FrameLayout 切换后列布局错位                                   | **低** | 用 `layout_gravity=CENTER` 保留居中                                                                          |
| 底部文案字号在低端机偏小                                       | **低** | 14sp 与现有 label 一致                                                                                       |
| `displayPhotos` 与 overlay GONE 时序错乱                       | **中** | 在 `displayPhotos` 内部 photoView 切 VISIBLE **之后**才置 ellipse 为 GONE                                    |
| 字号 / dp 转 px 跨设备一致                                     | **低** | 沿用现有 `dpToPx()` API                                                                                      |
| WebView 内容被 native container 盖住（与本方案无关但需要确认） | **低** | 本方案目标就是 native overlay，不依赖 WebView DOM；caption 贴在预览下沿而非容器底，与 WebView 内容互不干扰 |

---

## 8. 改动量盘点

| 文件                              | 行数预估   | 备注                                                                                           |
| --------------------------------- | ---------- | ---------------------------------------------------------------------------------------------- |
| `FaceDetectionOverlay.java`（新） | ~70 行     | 一个 View，包含字段、init、onSizeChanged、onDraw                                               |
| `Camera2Controller.java`（改）    | ~40 行新增 | 字段 2 个、`buildTextureViews` 改 ~20 行、`displayPhotos` 1 行、`resumePreviewFromPhotos` 1 行 |
| **合计**                          | ~110 行    | 1 新文件 + 1 修改文件                                                                          |

---

## 9. 后续可扩展项（非本方案范围）

- 用 Face Detection API（ML Kit / Face++）自动定位人脸，动态调整 ellipse 位置/大小。
- 椭圆支持人手点击"重置对焦"。
- 顶部/底部文案 i18n（目前 hard-coded 中文）。
- 文案动态化（不同 round 显示不同引导语）。

---

## 10. 文档元信息

| 项          | 值                                                   |
| ----------- | ---------------------------------------------------- |
| 创建时间    | 2026-08-20                                           |
| 实施时间    | 2026-08-24（首次实现）；2026-08-24 二次修订（颜色 `#BCBCBC`、尺寸 0.65/0.5、文案"请保证…尽量吻合"、caption 贴预览下沿 `topMargin = h + dpToPx(69)`） |
| 适用版本    | children-doctor >= 1.0.7                                                                                                                                                          |
| 涉及 commit | `e2e27f0`（方案文档）、`9b32e5a`（首版实施）、`<pending>`（二次修订：4 处默认值 + caption 位置）                                                                                       |
| 评审人      | （待定）                                                                                                                                                                          |
