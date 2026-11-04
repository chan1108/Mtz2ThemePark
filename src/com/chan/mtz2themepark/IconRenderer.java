package com.chan.mtz2themepark;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** 按主题的蒙版、底板、描边，把应用原图标渲染成同风格 PNG。 */
final class IconRenderer {
    static final int SIZE = 216;
    private static final int SAMPLE = 432;

    private final Bitmap mask, pattern, border;
    private final float scale;
    private final float maskSize;
    private final Paint filter = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint maskPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    IconRenderer(ThemeArchive theme) throws IOException {
        Bitmap themeMask = theme.readBitmap("icon_mask");
        mask = themeMask != null ? fit(themeMask) : defaultMask();
        Bitmap themePattern = theme.readBitmap("icon_pattern");
        pattern = themePattern != null ? fit(themePattern) : null;
        Bitmap themeBorder = theme.readBitmap("icon_border");
        border = themeBorder != null ? fit(themeBorder) : null;
        scale = theme.iconScale;
        maskSize = alphaBounds(mask).width();
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    byte[] renderPng(Drawable icon) throws IOException {
        Bitmap bitmap = render(icon);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("PNG 编码失败");
        bitmap.recycle();
        return out.toByteArray();
    }

    private Bitmap render(Drawable icon) {
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        if (pattern != null) c.drawBitmap(pattern, 0, 0, filter);
        else c.drawColor(Color.WHITE);

        if (icon instanceof AdaptiveIconDrawable) drawAdaptive(c, (AdaptiveIconDrawable) icon);
        else drawLegacy(c, icon);

        c.drawBitmap(mask, 0, 0, maskPaint);
        if (border != null) c.drawBitmap(border, 0, 0, filter);
        return out;
    }

    /** 自适应图标的两层各 108dp，可见区域是中间 72dp：让这 72dp 正好铺满蒙版。 */
    private void drawAdaptive(Canvas c, AdaptiveIconDrawable icon) {
        int full = Math.round(maskSize * 108f / 72f);
        int left = (SIZE - full) / 2;
        Rect bounds = new Rect(left, left, left + full, left + full);
        for (Drawable layer : new Drawable[]{icon.getBackground(), icon.getForeground()}) {
            if (layer == null) continue;
            layer.setBounds(bounds);
            layer.draw(c);
        }
    }

    /** 普通图标：先量出实际内容，方形内容放大铺满，其他形状缩小后放在底板上。 */
    private void drawLegacy(Canvas c, Drawable icon) {
        Bitmap src = Bitmap.createBitmap(SAMPLE, SAMPLE, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, SAMPLE, SAMPLE);
        icon.draw(new Canvas(src));
        Rect content = alphaBounds(src);
        if (content.isEmpty()) { src.recycle(); return; }
        float coverage = opaqueCoverage(src, content);
        float w = content.width(), h = content.height();
        float target = maskSize;
        float factor;
        if (coverage >= 0.88f) factor = target * scale / Math.min(w, h);
        else if (coverage >= 0.70f) factor = target * 0.86f / Math.max(w, h);
        else factor = target * 0.72f / Math.max(w, h);
        float dw = w * factor, dh = h * factor;
        RectF dst = new RectF((SIZE - dw) / 2f, (SIZE - dh) / 2f, (SIZE + dw) / 2f, (SIZE + dh) / 2f);
        c.drawBitmap(src, content, dst, filter);
        src.recycle();
    }

    private static Bitmap fit(Bitmap b) {
        if (b.getWidth() == SIZE && b.getHeight() == SIZE) return b;
        return Bitmap.createScaledBitmap(b, SIZE, SIZE, true);
    }

    private static Bitmap defaultMask() {
        Bitmap b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        new Canvas(b).drawRoundRect(new RectF(7, 7, SIZE - 7, SIZE - 7), 48, 48, p);
        return b;
    }

    private static Rect alphaBounds(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int l = w, t = h, r = -1, bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) > 16) {
                    if (x < l) l = x;
                    if (x > r) r = x;
                    if (y < t) t = y;
                    if (y > bottom) bottom = y;
                }
            }
        }
        return r < 0 ? new Rect() : new Rect(l, t, r + 1, bottom + 1);
    }

    private static float opaqueCoverage(Bitmap b, Rect area) {
        int w = area.width(), h = area.height();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, area.left, area.top, w, h);
        int opaque = 0;
        for (int p : px) if ((p >>> 24) >= 200) opaque++;
        return opaque / (float) px.length;
    }
}
