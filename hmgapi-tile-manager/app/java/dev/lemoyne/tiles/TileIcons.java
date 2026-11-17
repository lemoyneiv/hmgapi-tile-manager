package dev.lemoyne.tiles;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Crisp anti-aliased Material 3 vector icons drawn natively on Canvas (24x24 virtual viewport)
 * for Android 4.4 (API 19) with zero bitmap scaling artifacts.
 */
final class TileIcons extends Drawable {
    static final int GRID_ALL = 1;
    static final int PACKAGE = 2;
    static final int SYSTEM = 3;
    static final int CHECK = 4;
    static final int SHOW_ALL = 5;
    static final int REFRESH = 6;
    static final int COMPACT = 7;
    static final int SHIELD = 8;
    static final int CLOSE = 9;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final int kind;
    private int color;

    TileIcons(int kind, int color) {
        this.kind = kind;
        this.color = color;
    }

    void setColor(int c) {
        if (this.color != c) {
            this.color = c;
            invalidateSelf();
        }
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.width() <= 0 || b.height() <= 0) return;
        float size = Math.min(b.width(), b.height());
        float s = size / 24f;
        float ox = b.left + (b.width() - size) * 0.5f;
        float oy = b.top + (b.height() - size) * 0.5f;

        c.save();
        c.translate(ox, oy);

        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.0f * s);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        path.reset();

        switch (kind) {
            case GRID_ALL: {
                // 2x2 rounded launcher grid with filled top-right accent tile
                rect.set(3.5f * s, 3.5f * s, 10.3f * s, 10.3f * s);
                c.drawRoundRect(rect, 2.0f * s, 2.0f * s, p);
                rect.set(3.5f * s, 13.7f * s, 10.3f * s, 20.5f * s);
                c.drawRoundRect(rect, 2.0f * s, 2.0f * s, p);
                rect.set(13.7f * s, 13.7f * s, 20.5f * s, 20.5f * s);
                c.drawRoundRect(rect, 2.0f * s, 2.0f * s, p);

                p.setStyle(Paint.Style.FILL);
                rect.set(13.5f * s, 3.5f * s, 20.5f * s, 10.5f * s);
                c.drawRoundRect(rect, 2.2f * s, 2.2f * s, p);
                break;
            }
            case PACKAGE: {
                // Isometric 3D mod package cube
                path.moveTo(12f * s, 2.8f * s);
                path.lineTo(20f * s, 7.3f * s);
                path.lineTo(20f * s, 16.7f * s);
                path.lineTo(12f * s, 21.2f * s);
                path.lineTo(4f * s, 16.7f * s);
                path.lineTo(4f * s, 7.3f * s);
                path.close();
                c.drawPath(path, p);

                path.reset();
                path.moveTo(4.2f * s, 7.5f * s);
                path.lineTo(12f * s, 12.0f * s);
                path.lineTo(19.8f * s, 7.5f * s);
                c.drawPath(path, p);
                c.drawLine(12f * s, 12.0f * s, 12f * s, 21.0f * s, p);
                c.drawLine(8.0f * s, 5.1f * s, 16.0f * s, 9.6f * s, p);
                break;
            }
            case SYSTEM: {
                // Widescreen head-unit display + dash stand
                rect.set(2.8f * s, 4.5f * s, 21.2f * s, 16.2f * s);
                c.drawRoundRect(rect, 2.4f * s, 2.4f * s, p);
                c.drawLine(8.0f * s, 19.6f * s, 16.0f * s, 19.6f * s, p);
                c.drawLine(12.0f * s, 16.2f * s, 12.0f * s, 19.6f * s, p);
                // Subtle dual-pane indicator inside screen
                c.drawLine(8.2f * s, 7.5f * s, 8.2f * s, 13.2f * s, p);
                break;
            }
            case CHECK: {
                path.moveTo(4.8f * s, 12.5f * s);
                path.lineTo(9.8f * s, 17.5f * s);
                path.lineTo(19.4f * s, 7.2f * s);
                c.drawPath(path, p);
                break;
            }
            case SHOW_ALL: {
                // Eye icon (Show All / Visible)
                path.moveTo(2.4f * s, 12f * s);
                path.quadTo(12f * s, 4.0f * s, 21.6f * s, 12f * s);
                path.quadTo(12f * s, 20.0f * s, 2.4f * s, 12f * s);
                c.drawPath(path, p);
                c.drawCircle(12f * s, 12f * s, 2.8f * s, p);
                break;
            }
            case REFRESH: {
                // Circular refresh arc + arrowhead
                rect.set(4.5f * s, 4.5f * s, 19.5f * s, 19.5f * s);
                c.drawArc(rect, 35f, 290f, false, p);
                path.moveTo(15.2f * s, 4.3f * s);
                path.lineTo(19.6f * s, 5.2f * s);
                path.lineTo(18.7f * s, 9.6f * s);
                c.drawPath(path, p);
                break;
            }
            case COMPACT: {
                // Left-compacting tile + arrow closing empty slot
                rect.set(3.2f * s, 5.5f * s, 9.8f * s, 18.5f * s);
                c.drawRoundRect(rect, 1.8f * s, 1.8f * s, p);
                c.drawLine(20.5f * s, 12f * s, 12.8f * s, 12f * s, p);
                path.moveTo(16.2f * s, 8.6f * s);
                path.lineTo(12.6f * s, 12f * s);
                path.lineTo(16.2f * s, 15.4f * s);
                c.drawPath(path, p);
                break;
            }
            case SHIELD: {
                path.moveTo(12f * s, 3.0f * s);
                path.lineTo(19f * s, 6.0f * s);
                path.lineTo(19f * s, 11.5f * s);
                path.quadTo(19f * s, 17.5f * s, 12f * s, 21.0f * s);
                path.quadTo(5f * s, 17.5f * s, 5f * s, 11.5f * s);
                path.lineTo(5f * s, 6.0f * s);
                path.close();
                c.drawPath(path, p);
                c.drawLine(12f * s, 8.6f * s, 12f * s, 13.0f * s, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(12f * s, 16.2f * s, 1.1f * s, p);
                break;
            }
            case CLOSE: {
                c.drawLine(6.5f * s, 6.5f * s, 17.5f * s, 17.5f * s, p);
                c.drawLine(17.5f * s, 6.5f * s, 6.5f * s, 17.5f * s, p);
                break;
            }
        }
        c.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        p.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        p.setColorFilter(cf);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
