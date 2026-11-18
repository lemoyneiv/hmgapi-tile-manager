package dev.lemoyne.tiles;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Crisp anti-aliased Material 3 & Gen5W stock-gradient vector icons drawn natively on Canvas
 * (24x24 virtual viewport) for Android 4.4 (API 19) with zero bitmap scaling artifacts.
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

    // Stock Gen5W system app fallback glyphs (painted in Launcher_WP3's cyan->purple gradient)
    static final int SYS_EV = 10;
    static final int SYS_NAVI = 11;
    static final int SYS_MAP = 12;
    static final int SYS_PHONE = 13;
    static final int SYS_PROJECTION = 14;
    static final int SYS_RADIO = 15;
    static final int SYS_HD_RADIO = 16;
    static final int SYS_MEDIA = 17;
    static final int SYS_CONNECT = 18;
    static final int SYS_PAY = 19;
    static final int SYS_NOTIFICATIONS = 20;
    static final int SYS_MANUAL = 21;
    static final int SYS_VALET = 22;
    static final int SYS_QUIET = 23;
    static final int SYS_VOICE_MEMO = 24;
    static final int SYS_CLIMATE = 25;
    static final int SYS_SETUP = 26;
    static final int SYS_WEATHER = 27;
    static final int SYS_PASSENGER_TALK = 28;
    static final int SYS_CAMERA = 29;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final int kind;
    private int color;
    private final boolean stockGradient;

    TileIcons(int kind, int color) {
        this.kind = kind;
        this.color = color;
        this.stockGradient = false;
    }

    private TileIcons(int kind, boolean stockGradient) {
        this.kind = kind;
        this.color = 0xFF2AB8AA;
        this.stockGradient = stockGradient;
    }

    /** Creates a system glyph painted in Gen5W Launcher_WP3's stock cyan->purple gradient. */
    static TileIcons stockSystem(int kind) {
        return new TileIcons(kind, true);
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

        if (stockGradient) {
            // Exact EV6 / Ioniq 5 Launcher_WP3 stock tile gradient (cyan top-left -> purple bottom-right)
            int cStart = TileTheme.night ? 0xFF2EE0CE : 0xFF1CCBB0;
            int cMid = TileTheme.night ? 0xFF4F92E8 : 0xFF3E8AD6;
            int cEnd = TileTheme.night ? 0xFF8C72F2 : 0xFF7B57C8;
            p.setShader(new LinearGradient(
                    2f * s, 2f * s, 22f * s, 22f * s,
                    new int[] { cStart, cMid, cEnd },
                    new float[] { 0f, 0.52f, 1f },
                    Shader.TileMode.CLAMP));
            p.setColor(0xFFFFFFFF);
        } else {
            p.setShader(null);
            p.setColor(color);
        }
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
            case SYS_EV: {
                c.drawCircle(12f * s, 12f * s, 8.8f * s, p);
                p.setStyle(Paint.Style.FILL);
                path.moveTo(13.0f * s, 6.2f * s);
                path.lineTo(8.5f * s, 12.6f * s);
                path.lineTo(11.8f * s, 12.6f * s);
                path.lineTo(10.8f * s, 17.8f * s);
                path.lineTo(15.6f * s, 11.2f * s);
                path.lineTo(12.2f * s, 11.2f * s);
                path.close();
                c.drawPath(path, p);
                break;
            }
            case SYS_NAVI: {
                path.moveTo(12f * s, 3.2f * s);
                path.lineTo(19.6f * s, 19.8f * s);
                path.lineTo(12f * s, 16.0f * s);
                path.lineTo(4.4f * s, 19.8f * s);
                path.close();
                c.drawPath(path, p);
                break;
            }
            case SYS_MAP: {
                path.moveTo(12f * s, 20.6f * s);
                path.quadTo(6.2f * s, 14.5f * s, 6.2f * s, 9.8f * s);
                rect.set(6.2f * s, 4.0f * s, 17.8f * s, 15.6f * s);
                path.arcTo(rect, 180f, 180f, false);
                path.quadTo(17.8f * s, 14.5f * s, 12f * s, 20.6f * s);
                c.drawPath(path, p);
                c.drawCircle(12f * s, 9.8f * s, 2.3f * s, p);
                break;
            }
            case SYS_PHONE: {
                path.moveTo(6.6f * s, 4.5f * s);
                path.lineTo(9.8f * s, 4.5f * s);
                path.lineTo(11.2f * s, 8.4f * s);
                path.lineTo(9.1f * s, 10.2f * s);
                path.quadTo(11.0f * s, 13.4f * s, 13.8f * s, 14.9f * s);
                path.lineTo(15.6f * s, 12.8f * s);
                path.lineTo(19.5f * s, 14.2f * s);
                path.lineTo(19.5f * s, 17.4f * s);
                path.quadTo(19.5f * s, 19.8f * s, 16.8f * s, 19.8f * s);
                path.quadTo(6.2f * s, 18.8f * s, 4.2f * s, 7.2f * s);
                path.quadTo(4.2f * s, 4.5f * s, 6.6f * s, 4.5f * s);
                c.drawPath(path, p);
                break;
            }
            case SYS_PROJECTION: {
                rect.set(3.0f * s, 5.0f * s, 17.5f * s, 15.8f * s);
                c.drawRoundRect(rect, 2.2f * s, 2.2f * s, p);
                rect.set(14.5f * s, 9.2f * s, 21.0f * s, 19.5f * s);
                c.drawRoundRect(rect, 1.8f * s, 1.8f * s, p);
                path.moveTo(8.5f * s, 8.4f * s);
                path.lineTo(12.2f * s, 10.4f * s);
                path.lineTo(8.5f * s, 12.4f * s);
                path.close();
                c.drawPath(path, p);
                break;
            }
            case SYS_RADIO: {
                rect.set(3.5f * s, 8.2f * s, 20.5f * s, 19.5f * s);
                c.drawRoundRect(rect, 2.2f * s, 2.2f * s, p);
                c.drawLine(6.2f * s, 8.2f * s, 16.8f * s, 4.0f * s, p);
                c.drawCircle(15.6f * s, 13.8f * s, 2.4f * s, p);
                c.drawLine(6.8f * s, 12.2f * s, 10.8f * s, 12.2f * s, p);
                c.drawLine(6.8f * s, 15.6f * s, 10.8f * s, 15.6f * s, p);
                break;
            }
            case SYS_HD_RADIO: {
                c.drawCircle(12f * s, 12f * s, 2.2f * s, p);
                rect.set(6.5f * s, 6.5f * s, 17.5f * s, 17.5f * s);
                c.drawArc(rect, -55f, 110f, false, p);
                c.drawArc(rect, 125f, 110f, false, p);
                rect.set(3.2f * s, 3.2f * s, 20.8f * s, 20.8f * s);
                c.drawArc(rect, -55f, 110f, false, p);
                c.drawArc(rect, 125f, 110f, false, p);
                break;
            }
            case SYS_MEDIA: {
                c.drawCircle(7.6f * s, 17.4f * s, 2.6f * s, p);
                c.drawCircle(16.8f * s, 15.6f * s, 2.6f * s, p);
                path.moveTo(10.2f * s, 17.4f * s);
                path.lineTo(10.2f * s, 6.4f * s);
                path.lineTo(19.4f * s, 4.6f * s);
                path.lineTo(19.4f * s, 15.6f * s);
                c.drawPath(path, p);
                break;
            }
            case SYS_CONNECT: {
                // Connected-car signal arcs + vehicle roof silhouette
                path.moveTo(4.8f * s, 17.8f * s);
                path.lineTo(6.8f * s, 13.4f * s);
                path.lineTo(17.2f * s, 13.4f * s);
                path.lineTo(19.2f * s, 17.8f * s);
                c.drawPath(path, p);
                c.drawLine(3.8f * s, 17.8f * s, 20.2f * s, 17.8f * s, p);
                rect.set(7.8f * s, 6.8f * s, 16.2f * s, 15.2f * s);
                c.drawArc(rect, -140f, 100f, false, p);
                rect.set(4.8f * s, 3.8f * s, 19.2f * s, 18.2f * s);
                c.drawArc(rect, -140f, 100f, false, p);
                break;
            }
            case SYS_PAY: {
                rect.set(3.2f * s, 5.8f * s, 20.8f * s, 18.2f * s);
                c.drawRoundRect(rect, 2.2f * s, 2.2f * s, p);
                c.drawLine(3.2f * s, 10.0f * s, 20.8f * s, 10.0f * s, p);
                c.drawLine(6.8f * s, 14.5f * s, 11.5f * s, 14.5f * s, p);
                break;
            }
            case SYS_NOTIFICATIONS: {
                path.moveTo(6.2f * s, 16.5f * s);
                path.lineTo(17.8f * s, 16.5f * s);
                path.lineTo(16.5f * s, 10.2f * s);
                rect.set(7.5f * s, 5.2f * s, 16.5f * s, 14.2f * s);
                path.arcTo(rect, 0f, -180f, false);
                path.close();
                c.drawPath(path, p);
                rect.set(10.0f * s, 16.5f * s, 14.0f * s, 20.0f * s);
                c.drawArc(rect, 0f, 180f, false, p);
                break;
            }
            case SYS_MANUAL: {
                path.moveTo(12f * s, 6.2f * s);
                path.quadTo(8.0f * s, 4.8f * s, 3.8f * s, 5.8f * s);
                path.lineTo(3.8f * s, 18.2f * s);
                path.quadTo(8.0f * s, 17.2f * s, 12f * s, 18.8f * s);
                path.quadTo(16.0f * s, 17.2f * s, 20.2f * s, 18.2f * s);
                path.lineTo(20.2f * s, 5.8f * s);
                path.quadTo(16.0f * s, 4.8f * s, 12f * s, 6.2f * s);
                c.drawPath(path, p);
                c.drawLine(12f * s, 6.2f * s, 12f * s, 18.8f * s, p);
                break;
            }
            case SYS_VALET: {
                c.drawCircle(8.5f * s, 12.0f * s, 4.2f * s, p);
                c.drawLine(12.7f * s, 12.0f * s, 20.5f * s, 12.0f * s, p);
                c.drawLine(17.2f * s, 12.0f * s, 17.2f * s, 15.2f * s, p);
                c.drawLine(20.0f * s, 12.0f * s, 20.0f * s, 15.2f * s, p);
                break;
            }
            case SYS_QUIET: {
                path.moveTo(15.8f * s, 4.5f * s);
                path.quadTo(9.0f * s, 5.2f * s, 7.2f * s, 12.0f * s);
                path.quadTo(7.2f * s, 18.8f * s, 14.8f * s, 19.5f * s);
                path.quadTo(18.2f * s, 19.5f * s, 19.8f * s, 17.2f * s);
                path.quadTo(13.2f * s, 17.0f * s, 12.6f * s, 10.6f * s);
                path.quadTo(12.4f * s, 6.8f * s, 15.8f * s, 4.5f * s);
                c.drawPath(path, p);
                break;
            }
            case SYS_VOICE_MEMO: {
                rect.set(9.2f * s, 3.8f * s, 14.8f * s, 13.5f * s);
                c.drawRoundRect(rect, 2.8f * s, 2.8f * s, p);
                rect.set(6.2f * s, 7.5f * s, 17.8f * s, 16.8f * s);
                c.drawArc(rect, 0f, 180f, false, p);
                c.drawLine(12f * s, 16.8f * s, 12f * s, 20.2f * s, p);
                c.drawLine(8.8f * s, 20.2f * s, 15.2f * s, 20.2f * s, p);
                break;
            }
            case SYS_CLIMATE: {
                c.drawCircle(12f * s, 12f * s, 2.2f * s, p);
                for (int deg = 0; deg < 360; deg += 90) {
                    c.save();
                    c.rotate(deg, 12f * s, 12f * s);
                    path.reset();
                    path.moveTo(12f * s, 9.8f * s);
                    path.quadTo(9.2f * s, 5.2f * s, 13.2f * s, 4.2f * s);
                    path.quadTo(16.0f * s, 4.2f * s, 13.8f * s, 9.8f * s);
                    c.drawPath(path, p);
                    c.restore();
                }
                break;
            }
            case SYS_SETUP: {
                c.drawCircle(12f * s, 12f * s, 3.2f * s, p);
                c.drawCircle(12f * s, 12f * s, 6.8f * s, p);
                for (int deg = 0; deg < 360; deg += 45) {
                    c.save();
                    c.rotate(deg, 12f * s, 12f * s);
                    c.drawLine(12f * s, 3.0f * s, 12f * s, 5.2f * s, p);
                    c.restore();
                }
                break;
            }
            case SYS_WEATHER: {
                rect.set(5.0f * s, 11.2f * s, 19.5f * s, 18.8f * s);
                c.drawRoundRect(rect, 3.8f * s, 3.8f * s, p);
                rect.set(8.2f * s, 7.6f * s, 15.8f * s, 15.2f * s);
                c.drawArc(rect, 180f, 180f, false, p);
                break;
            }
            case SYS_PASSENGER_TALK: {
                rect.set(3.5f * s, 5.2f * s, 18.5f * s, 15.8f * s);
                c.drawRoundRect(rect, 3.0f * s, 3.0f * s, p);
                path.moveTo(7.2f * s, 15.8f * s);
                path.lineTo(5.8f * s, 19.4f * s);
                path.lineTo(10.6f * s, 15.8f * s);
                c.drawPath(path, p);
                rect.set(15.0f * s, 7.5f * s, 21.2f * s, 13.8f * s);
                c.drawArc(rect, -55f, 110f, false, p);
                break;
            }
            case SYS_CAMERA: {
                rect.set(3.5f * s, 6.8f * s, 20.5f * s, 18.8f * s);
                c.drawRoundRect(rect, 2.2f * s, 2.2f * s, p);
                c.drawCircle(12f * s, 12.8f * s, 3.2f * s, p);
                c.drawLine(8.5f * s, 6.8f * s, 10.0f * s, 4.8f * s, p);
                c.drawLine(10.0f * s, 4.8f * s, 14.0f * s, 4.8f * s, p);
                c.drawLine(14.0f * s, 4.8f * s, 15.5f * s, 6.8f * s, p);
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
