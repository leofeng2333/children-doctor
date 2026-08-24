package com.children.doctor.plugins.dualcamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 拍摄预览对焦辅助：橙色虚线椭圆框（仅装饰，不参与触摸链路）。
 *
 * 业务定位：
 * - 提示用户"把脸放进椭圆里"；
 * - 与底部 caption 文案配套出现，仅在 native 预览开启时显示，
 *   拍照后会被外层 Camera2Controller 显式 GONE。
 *
 * 设计要点：
 * - 不拦截触摸事件（{@code clickable = focusable = false}），避免干扰拍照按钮/重拍按钮；
 * - 椭圆相对当前 View 中心、宽×80%、高×60%，与脸型贴合更自然；
 * - stroke 模式画线、中央完全透明，不遮挡摄像头画面；
 * - 不持有任何 session 引用，纯渲染单元，便于复用。
 */
public class FaceDetectionOverlay extends View {

    private final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF overlayRect = new RectF();

    private final int colorArgb;
    private final float widthRatio;
    private final float heightRatio;
    private final float strokeWidthPx;
    private final float dashOnPx;
    private final float dashOffPx;

    public FaceDetectionOverlay(Context context) {
        this(context, Color.parseColor("#BCBCBC"), 0.65f, 0.5f, 4f, 24f, 16f);
    }

    public FaceDetectionOverlay(
            Context context,
            int colorArgb,
            float widthRatio,
            float heightRatio,
            float strokeWidthPx,
            float dashOnPx,
            float dashOffPx) {
        super(context);
        this.colorArgb = colorArgb;
        this.widthRatio = widthRatio;
        this.heightRatio = heightRatio;
        this.strokeWidthPx = strokeWidthPx;
        this.dashOnPx = dashOnPx;
        this.dashOffPx = dashOffPx;

        // 仅装饰：不拦截触摸，不参与焦点链
        setClickable(false);
        setFocusable(false);
        // 不参与父 View 的 hit test
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public FaceDetectionOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
        // XML 入口目前未使用，给一个安全默认；任何真实使用都走上面的构造器
        this.colorArgb = Color.parseColor("#BCBCBC");
        this.widthRatio = 0.65f;
        this.heightRatio = 0.6f;
        this.strokeWidthPx = 4f;
        this.dashOnPx = 24f;
        this.dashOffPx = 16f;
        setClickable(false);
        setFocusable(false);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 计算椭圆矩形（相对中心）
        float ellipseW = w * widthRatio;
        float ellipseH = h * heightRatio;
        float left = (w - ellipseW) / 2f;
        float top = (h - ellipseH) / 2f;
        overlayRect.set(left, top, left + ellipseW, top + ellipseH);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (overlayRect.width() <= 0 || overlayRect.height() <= 0) return;

        overlayPaint.reset();
        overlayPaint.setAntiAlias(true);
        overlayPaint.setStyle(Paint.Style.STROKE);
        overlayPaint.setStrokeWidth(strokeWidthPx);
        overlayPaint.setColor(colorArgb);
        overlayPaint.setPathEffect(new DashPathEffect(new float[]{dashOnPx, dashOffPx}, 0f));

        canvas.drawOval(overlayRect, overlayPaint);
    }
}
