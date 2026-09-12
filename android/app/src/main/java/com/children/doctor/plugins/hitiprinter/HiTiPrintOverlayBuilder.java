package com.children.doctor.plugins.hitiprinter;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * path-current 打印叠加图层构建器：
 * 把"主图 + qrcode.jpg + home-icon.png + 文案"合成一张符合设计稿的临时打印图。
 *
 * <p>设计稿：用户提供的 100mm × 150mm 相纸排版（{@code 相纸.ai}），从用户分享的设计图识别得到。
 * 设计元素：
 * <ul>
 *   <li>背景：珊瑚橙 → 纯白 垂直渐变（顶部 #FFA873 浅珊瑚橙 → 底部 #FFFFFF 纯白）</li>
 *   <li>主图：居中靠上，letterbox-fit 到主图区（保留原图比例 + 主图原生内容/白底），
 *       主图占据画布高度的 ~73%（portrait，如 paperType=2=1240×1844）</li>
 *   <li>底部行（约占画布 22%~24% 高度）：
 *       <ul>
 *         <li>左：QR 码（来自 assets/print-overlay/qrcode.jpg），固定在左侧 leftPad，无边框，尺寸 = {@code min(bottomH × 0.78, paperW × 0.32)}</li>
 *         <li>右列（垂直堆叠，总高 ≈ QR 高度；在 QR 右侧可用空间内水平居中）：
 *           <ul>
 *             <li>顶：小熊猫吉祥物 icon（来自 assets/print-overlay/home-icon.png），尺寸 = qrSize × 0.50，
 *                 列内左对齐</li>
 *             <li>中："扫码关注"（normal，不加粗），字号 = qrSize × 0.18，
 *                 以 icon 中心 x 为锚点水平居中（{@code Paint.Align.CENTER}）</li>
 *             <li>底："获取更多儿童口腔健康知识"（normal，与文字 1 同号），同上方式水平居中</li>
 *           </ul>
 *         </li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p>输出尺寸：与 HiTi SDK 各 PaperSize 的物理像素对齐（动态查 {@link #paperTypeToPixels}）。
 * 默认走 paperType=2（4×6 portrait 1240×1844，对应 100×150mm 设计稿），也支持其它 PaperSize（3/4/5/6）。
 * 用户要求：paperType 与画布物理像素不变；方向必须与设计稿一致（竖版）。
 *
 * <p>asset 路径：二维码与图标从 {@code assets/print-overlay/} 打包到 APK（拷贝自
 * {@code src/assets/images/qrcode.jpg} 与 {@code src/assets/images/home-icon.png}）。
 * 这样运行时不依赖网络/TS bundle。
 *
 * <p>产物是 {@code ExternalCacheDir/hiti_overlay_<ts>.jpg} 的临时文件，
 * 由调用方在打印完成后用 {@link #deleteQuietly(File)} 删除；SDK 读盘时再读此文件。
 */
public class HiTiPrintOverlayBuilder {
    private static final String TAG = "HiTiPrintOverlay";
    private static final String ASSET_DIR = "print-overlay";
    private static final String QRCODE_ASSET = "qrcode.jpg";
    private static final String ICON_ASSET = "home-icon.png";

    private static final String TEXT_LINE_1 = "扫码关注";
    private static final String TEXT_LINE_2 = "获取更多儿童口腔健康知识";

    private static final int BG_TOP_COLOR = 0xFFFFA873;     // 浅珊瑚橙
    private static final int BG_BOTTOM_COLOR = 0xFFFFFFFF;  // 纯白（用户最新要求：渐变至底部变纯白）
    private static final int TEXT_COLOR = 0xFF111111;
    private static final int TEXT_QR_BUFFER_PX = 40;       // 文字左缘与 QR 右缘之间的最小水平安全间距（像素）

    /** Overlay JPEG 输出质量（与 SDK/纸照片期望一致）。 */
    private static final int OVERLAY_JPEG_QUALITY = 92;

    private final Context context;
    /** PrintLogger 可能为 null（plugin load 时机异常等），所有日志都做空检查。 */
    private final PrintLogger printLogger;

    public HiTiPrintOverlayBuilder(Context context, PrintLogger printLogger) {
        this.context = context.getApplicationContext();
        this.printLogger = printLogger;
    }

    private void logD(String msg) {
        Log.d(TAG, msg);
        if (printLogger != null) printLogger.java(TAG, msg);
    }

    private void logE(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        if (printLogger != null) printLogger.java(TAG, msg + (t == null ? "" : " | " + t));
    }

    /**
     * 按 paperType 合成叠加图层，并写入 {@code ExternalCacheDir/hiti_overlay_<ts>.jpg}。
     * 返回临时文件绝对路径；调用方负责用 {@link #deleteQuietly(File)} 删除。
     *
     * <p>出错时（asset 缺失、bitmap 解码失败、IO 失败等）抛 IOException，
     * bitmap 中间产物已尽量 recycle。
     *
     * @param mainImgPath 主图绝对路径（来自 base64 → 文件落地的临时路径）
     * @param paperType   HiTi PaperSize 枚举值：2=4×6, 3=5×7, 4=6×8, 5=4×6 split 2up, 6=6×6
     */
    public File composeAndWriteToCache(String mainImgPath, int paperType) throws IOException {
        int[] paper = paperTypeToPixels(paperType);
        final int paperW = paper[0];
        final int paperH = paper[1];
        final boolean portrait = paperH > paperW;
        logD("composeAndWriteToCache: paper=" + paperW + "x" + paperH
                + " (paperType=" + paperType + ", portrait=" + portrait + ")");

        Bitmap mainBmp = BitmapFactory.decodeFile(mainImgPath);
        if (mainBmp == null) {
            throw new IOException("HiTiPrintOverlay: BitmapFactory.decodeFile returned null for " + mainImgPath);
        }
        logD("composeAndWriteToCache: main bitmap " + mainBmp.getWidth() + "x" + mainBmp.getHeight());

        Bitmap qrcodeBmp = loadBitmapFromAsset(QRCODE_ASSET);
        if (qrcodeBmp == null) {
            mainBmp.recycle();
            throw new IOException("HiTiPrintOverlay: failed to load qrcode.jpg from assets/" + ASSET_DIR);
        }
        Bitmap iconBmp = loadBitmapFromAsset(ICON_ASSET);
        if (iconBmp == null) {
            mainBmp.recycle();
            qrcodeBmp.recycle();
            throw new IOException("HiTiPrintOverlay: failed to load home-icon.png from assets/" + ASSET_DIR);
        }
        logD("composeAndWriteToCache: qrcode " + qrcodeBmp.getWidth() + "x" + qrcodeBmp.getHeight()
                + ", icon " + iconBmp.getWidth() + "x" + iconBmp.getHeight());

        Bitmap canvasBmp = Bitmap.createBitmap(paperW, paperH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(canvasBmp);
        Paint bgPaint = new Paint();
        bgPaint.setShader(new LinearGradient(0, 0, 0, paperH,
                BG_TOP_COLOR, BG_BOTTOM_COLOR, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, paperW, paperH, bgPaint);

        try {
            if (portrait) {
                layoutPortrait(c, mainBmp, qrcodeBmp, iconBmp, paperW, paperH);
            } else {
                layoutLandscape(c, mainBmp, qrcodeBmp, iconBmp, paperW, paperH);
            }
        } catch (Throwable t) {
            mainBmp.recycle();
            qrcodeBmp.recycle();
            iconBmp.recycle();
            canvasBmp.recycle();
            logE("composeAndWriteToCache: layout drawing failed", t);
            throw new IOException("HiTiPrintOverlay: layout drawing failed: "
                    + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()));
        }

        File cache = context.getExternalCacheDir();
        if (cache == null) {
            mainBmp.recycle();
            qrcodeBmp.recycle();
            iconBmp.recycle();
            canvasBmp.recycle();
            throw new IOException("HiTiPrintOverlay: External cache dir not available");
        }
        File outFile = new File(cache, "hiti_overlay_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            canvasBmp.compress(Bitmap.CompressFormat.JPEG, OVERLAY_JPEG_QUALITY, fos);
        }
        logD("composeAndWriteToCache: written " + outFile.getAbsolutePath()
                + " (" + outFile.length() + " bytes)");

        mainBmp.recycle();
        qrcodeBmp.recycle();
        iconBmp.recycle();
        // canvasBmp 数据已通过 compress() 复制到 fos，file 在磁盘上有完整副本；可安全 recycle。
        canvasBmp.recycle();
        return outFile;
    }

    /**
     * 底部行布局（layoutPortrait / layoutLandscape 共用）。
     *
     * <p>底部行结构：左 = QR（固定在左Pad），右 = 右列（icon 顶部 + 两行同号文字底部）。
     *
     * <p>右列水平居中策略：
     * <ul>
     *   <li>列宽 = {@code max(iconSize, text2MeasuredWidth)}（用 {@link Paint#measureText} 精确测量，
     *       避免中文字符实际渲染宽度比 {@code chars × textSize} 估算值大而导致的"看起来居中其实偏宽"问题）</li>
     *   <li>列在 QR 右侧的可用空间内水平居中：
     *       {@code columnStartX = qrRight + (paperW - qrRight - columnWidth) / 2}</li>
     *   <li>图标在列内左对齐（{@code iconX = columnStartX}）</li>
     *   <li>两行文字以 {@code iconCenterX} 为锚点水平居中（{@code Paint.Align.CENTER}），
     *       即"以图标中心为锚"对齐</li>
     * </ul>
     *
     * <p>右列总高 ≈ QR 高度（比例分配）：
     * icon 50% + gap1 14% + 文字1 16% + gap2 4% + 文字2 16% = 100%
     *
     * <p>调整（用户最新要求）：
     * <ul>
     *   <li>右列在 QR 右侧的可用空间内水平居中（之前 icon 在列内左对齐，列在 QR 旁从左开始）</li>
     *   <li>用 {@link Paint#measureText} 精确测量文字宽度，从公式层面避免文字与 QR 重合
     *       （不再依赖 {@code chars × textSize} 估算 + buffer）</li>
     *   <li>整行水平居中（QR + 间距 + 右列整体在画布水平居中，左右留白相等）</li>
     *   <li>文字仍以 icon 中心 x 为锚点水平居中（{@code Paint.Align.CENTER}）</li>
     *   <li>图标 0.50、文字 0.18（保持上次确认值）</li>
     * </ul>
     */
    private void layoutBottomRow(Canvas c, Bitmap qrcodeBmp, Bitmap iconBmp,
                                 int paperW, int bottomTop, int bottomH) {
        int qrSize = (int) Math.min(bottomH * 0.78f, paperW * 0.32f);
        int qrY = bottomTop + (bottomH - qrSize) / 2;

        // 右列尺寸：图标 0.50，文字 0.16（文字调小一号后省出来的 0.04 补到 gap1），gap2 0.04
        int iconSize = (int) (qrSize * 0.50f);
        float gap1 = qrSize * 0.14f;
        float gap2 = qrSize * 0.01f;
        float textSize = qrSize * 0.14f;

        // 用 measureText 精确测量文字宽度（不再用 chars × textSize 估算，避免中文字符实际宽度偏大导致视觉重合）
        Paint measurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        measurePaint.setTextSize(textSize);
        measurePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        int text1Width = Math.round(measurePaint.measureText(TEXT_LINE_1));
        int text2Width = Math.round(measurePaint.measureText(TEXT_LINE_2));

        // 整行总宽 = QR + 间距 + 右列宽（取图标宽和文字2宽的较大者）
        // gap：保证 文字1 左缘（= iconCenterX - text1Width/2）距 QR 右缘 ≥ TEXT_QR_BUFFER_PX
        //   iconCenterX = qrRight + gap + iconSize/2
        //   text1LeftEdge = qrRight + gap + iconSize/2 - text1Width/2
        //   ⇒ gap ≥ text1Width/2 - iconSize/2 + TEXT_QR_BUFFER_PX
        int gapFromBuffer = text1Width / 2 - iconSize / 2 + TEXT_QR_BUFFER_PX;
        int gap = Math.max((int) (paperW * 0.04f), gapFromBuffer);
        int columnWidth = Math.max(iconSize, text2Width);
        int totalWidth = qrSize + gap + columnWidth;

        // 整行水平居中（左右留白相等）
        int leftMargin = (paperW - totalWidth) / 2;
        int qrX = leftMargin;
        int qrRight = qrX + qrSize;
        int columnStartX = qrRight + gap;
        int iconX = columnStartX;
        int iconCenterX = columnStartX + iconSize / 2;
        int iconY = qrY;

        // QR 码
        c.drawBitmap(qrcodeBmp, null,
                new Rect(qrX, qrY, qrX + qrSize, qrY + qrSize), null);

        // 图标
        c.drawBitmap(iconBmp, null,
                new Rect(iconX, iconY, iconX + iconSize, iconY + iconSize), null);

        // 文字 paint（共用，统一字号 normal）
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(TEXT_COLOR);
        textPaint.setTextSize(textSize);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));

        // 文字 1（4 字）：以 icon 中心 x 为锚点水平居中
        textPaint.setTextAlign(Paint.Align.CENTER);
        float text1TopY = iconY + iconSize + gap1;
        Paint.FontMetrics fm1 = textPaint.getFontMetrics();
        float baseline1 = text1TopY - fm1.ascent;
        c.drawText(TEXT_LINE_1, iconCenterX, baseline1, textPaint);
        int text1LeftEdge = iconCenterX - text1Width / 2;

        // 文字 2（12 字）：LEFT 对齐到 文字 1 的左缘
        // 关键：文字 2 宽 12 字，居中后会向左延伸穿过 QR；改为 LEFT 对齐到 text1 的左缘，
        // 既保证文字 2 不向左越过文字 1，也不会与 QR 重合（text1 左缘已确保 ≥ QR 右缘+buffer）。
        textPaint.setTextAlign(Paint.Align.LEFT);
        float text2TopY = text1TopY + textSize + gap2;
        Paint.FontMetrics fm2 = textPaint.getFontMetrics();
        float baseline2 = text2TopY - fm2.ascent;
        c.drawText(TEXT_LINE_2, text1LeftEdge, baseline2, textPaint);
        int text2RightEdge = text1LeftEdge + text2Width;

        logD("bottom row: qrSize=" + qrSize + " iconSize=" + iconSize
                + " textSize=" + (int) textSize + " gap=" + gap
                + " text1MeasuredW=" + text1Width + " text2MeasuredW=" + text2Width
                + " columnW=" + columnWidth + " totalW=" + totalWidth
                + " leftMargin=" + leftMargin + " rightMargin=" + (paperW - leftMargin - totalWidth)
                + " qrX=" + qrX + " columnStartX=" + columnStartX
                + " iconX=" + iconX + " iconCenterX=" + iconCenterX
                + " qrRight=" + qrRight
                + " text1Left=" + text1LeftEdge
                + " text1_buffer_from_qrRight=" + (text1LeftEdge - qrRight)
                + " text2Left=" + text1LeftEdge + " text2Right=" + text2RightEdge
                + " text2Right_overflow=" + Math.max(0, text2RightEdge - paperW));
    }

    /**
     * Landscape / Square 布局（paperType=6 方形 1844×1844，或将来其它非竖版画布）：
     * <ul>
     *   <li>上半部分（顶部 ~3% 间隔 + 约 73% 高度）：主图 letterbox-fit 居中</li>
     *   <li>下半部分（约 24% 高度）：{@link #layoutBottomRow}</li>
     * </ul>
     */
    private void layoutLandscape(Canvas c, Bitmap mainBmp, Bitmap qrcodeBmp, Bitmap iconBmp,
                                 int paperW, int paperH) {
        int mainAreaH = (int) (paperH * 0.73f);
        int mainMargin = (int) (paperH * 0.03f);
        int sideMargin = (int) (paperW * 0.03f);
        Bitmap fitted = letterboxFit(mainBmp, paperW - 2 * sideMargin, mainAreaH - mainMargin);
        int mainX = (paperW - fitted.getWidth()) / 2;
        int mainY = mainMargin;
        c.drawBitmap(fitted, mainX, mainY, null);
        logD("layoutLandscape: main drawn at (" + mainX + "," + mainY
                + ") size " + fitted.getWidth() + "x" + fitted.getHeight());
        if (fitted != mainBmp) fitted.recycle();

        int bottomTop = mainY + mainAreaH;
        int bottomH = paperH - bottomTop;
        layoutBottomRow(c, qrcodeBmp, iconBmp, paperW, bottomTop, bottomH);
    }

    /**
     * Portrait 布局（paperType=2/3/4/5，全部竖版）：
     * <ul>
     *   <li>上半部分（顶部 ~5% 间隔 + 约 73% 高度）：主图 letterbox-fit 居中</li>
     *   <li>下半部分（约 22% 高度）：{@link #layoutBottomRow}</li>
     * </ul>
     */
    private void layoutPortrait(Canvas c, Bitmap mainBmp, Bitmap qrcodeBmp, Bitmap iconBmp,
                                int paperW, int paperH) {
        int mainAreaH = (int) (paperH * 0.73f);
        int mainMargin = (int) (paperW * 0.05f);
        Bitmap fitted = letterboxFit(mainBmp, paperW - 2 * mainMargin, mainAreaH - mainMargin);
        int mainX = (paperW - fitted.getWidth()) / 2;
        int mainY = mainMargin;
        c.drawBitmap(fitted, mainX, mainY, null);
        if (fitted != mainBmp) fitted.recycle();
        logD("layoutPortrait: main drawn at (" + mainX + "," + mainY
                + ") size " + fitted.getWidth() + "x" + fitted.getHeight());

        int bottomTop = mainY + mainAreaH;
        int bottomH = paperH - bottomTop;
        layoutBottomRow(c, qrcodeBmp, iconBmp, paperW, bottomTop, bottomH);
    }

    /**
     * Letterbox-fit：等比缩放 src 到完全在 {@code (maxW, maxH)} 内，
     * 不裁切。返回缩放后的新 Bitmap；缩放前后尺寸相同时直接返回 src（避免 createScaledBitmap 内存分配）。
     */
    private Bitmap letterboxFit(Bitmap src, int maxW, int maxH) {
        if (src == null) return null;
        int srcW = src.getWidth();
        int srcH = src.getHeight();
        if (srcW <= 0 || srcH <= 0 || maxW <= 0 || maxH <= 0) return src;
        if (srcW == maxW && srcH == maxH) return src;
        float scale = Math.min((float) maxW / (float) srcW, (float) maxH / (float) srcH);
        int newW = Math.max(1, Math.round((float) srcW * scale));
        int newH = Math.max(1, Math.round((float) srcH * scale));
        if (newW == srcW && newH == srcH) return src;
        return Bitmap.createScaledBitmap(src, newW, newH, true);
    }

    private Bitmap loadBitmapFromAsset(String filename) {
        try {
            AssetManager am = context.getAssets();
            try (InputStream in = am.open(ASSET_DIR + "/" + filename)) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (IOException e) {
            Log.e(TAG, "loadBitmapFromAsset: " + filename + " failed", e);
            if (printLogger != null) printLogger.java(TAG,
                    "loadBitmapFromAsset(" + filename + ") failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * paperType → PaperSize 物理像素查表。与
     * {@link HiTiPrinterManager_Current#paperTypeToTargetPixels} 保持一致。
     * 未知 paperType 落到默认 paperType=2 (4×6 landscape 1844×1240)。
     */
    private static int[] paperTypeToPixels(int paperType) {
        switch (paperType) {
            case 3: return new int[]{1548, 2140}; // 5×7 portrait
            case 4: return new int[]{1844, 2434}; // 6×8 portrait
            case 5: return new int[]{1240, 1844}; // 6×4 split 2up portrait
            case 6: return new int[]{1844, 1844}; // 6×6 square
            case 2:
            default: return new int[]{1240, 1844}; // 4×6 portrait (DEFAULT, 设计稿 100×150mm 竖版)
        }
    }

    /**
     * 静默删除：打印完成 / 失败后调用。空文件 / null 都安全。
     */
    public static void deleteQuietly(File f) {
        if (f == null || !f.exists()) return;
        if (!f.delete()) {
            Log.w(TAG, "Failed to delete temp file: " + f.getAbsolutePath());
        }
    }
}
