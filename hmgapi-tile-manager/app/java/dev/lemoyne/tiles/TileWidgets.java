package dev.lemoyne.tiles;

import static dev.lemoyne.tiles.TileTheme.*;

import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Material Design 3 components built natively for Android API 19 (KitKat):
 * - M3Switch: animated 52x32dp stadium switch with 16->24->28dp thumb, state halo & checkmark
 * - TileCardView: Gen5W launcher tile card with live PackageManager icon, mod/system badge & switch
 * - M3 Filled, Tonal, Outlined, and Circular Icon buttons with pressed state layers
 */
final class TileWidgets {
    static final int BTN_FILLED = 1;
    static final int BTN_TONAL = 2;
    static final int BTN_OUTLINED = 3;

    interface ToggleListener {
        void onToggle(boolean checked);
    }

    static final class TileItem {
        final String key;
        final String packageName;
        final String className;
        final String title;
        final String kind; // "package" or "system"
        final String modId;
        final boolean orphan;

        TileItem(String key, String packageName, String className, String title,
                 String kind, String modId, boolean orphan) {
            this.key = key;
            this.packageName = packageName;
            this.className = className;
            this.title = title;
            this.kind = kind;
            this.modId = modId;
            this.orphan = orphan;
        }

        boolean isPackage() {
            return "package".equals(kind);
        }
    }

    static int blendColor(int c1, int c2, float t) {
        if (t <= 0f) return c1;
        if (t >= 1f) return c2;
        int a = Math.round(Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * t);
        int r = Math.round(Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t);
        int g = Math.round(Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t);
        int b = Math.round(Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t);
        return Color.argb(a, r, g, b);
    }

    /** Material Design 3 52x32dp stadium switch inside a 58x38dp touch target. */
    static final class M3Switch extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final Path checkPath = new Path();
        private final float d;
        private boolean checked;
        private boolean pressedDown;
        private float progress;
        private ValueAnimator anim;
        private final ToggleListener listener;

        M3Switch(Context c, boolean initial, ToggleListener onToggle) {
            super(c);
            this.d = c.getResources().getDisplayMetrics().density;
            this.checked = initial;
            this.progress = initial ? 1f : 0f;
            this.listener = onToggle;
            setClickable(true);
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!isEnabled()) return;
                    setChecked(!checked, true);
                    if (listener != null) listener.onToggle(checked);
                }
            });
        }

        void setChecked(boolean next, boolean animate) {
            if (checked == next && (anim == null || !anim.isRunning())) {
                float target = next ? 1f : 0f;
                if (Math.abs(progress - target) > 0.01f) {
                    progress = target;
                    invalidate();
                }
                return;
            }
            checked = next;
            final float target = next ? 1f : 0f;
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
            if (!animate) {
                progress = target;
                invalidate();
                return;
            }
            anim = ValueAnimator.ofFloat(progress, target);
            anim.setDuration(165);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator va) {
                    progress = ((Float) va.getAnimatedValue()).floatValue();
                    invalidate();
                }
            });
            anim.start();
        }

        boolean isChecked() {
            return checked;
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setAlpha(enabled ? 1f : 0.38f);
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN && isEnabled()) {
                pressedDown = true;
                invalidate();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                pressedDown = false;
                invalidate();
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            setMeasuredDimension(Math.round(58 * d), Math.round(38 * d));
        }

        @Override
        protected void onDraw(Canvas c) {
            float vw = getWidth(), vh = getHeight();
            float tw = 52f * d, th = 32f * d;
            float left = (vw - tw) * 0.5f;
            float top = (vh - th) * 0.5f;
            float r = th * 0.5f;

            trackRect.set(left + 1f * d, top + 1f * d, left + tw - 1f * d, top + th - 1f * d);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(blendColor(SURFACE_HIGHEST, PRIMARY, progress));
            c.drawRoundRect(trackRect, r, r, paint);

            if (progress < 0.99f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.8f * d * (1f - progress));
                paint.setColor(withAlpha(OUTLINE, Math.round(255 * (1f - progress))));
                c.drawRoundRect(trackRect, r, r, paint);
            }

            float startCx = left + th * 0.5f;
            float endCx = left + tw - th * 0.5f;
            float cx = startCx + (endCx - startCx) * progress;
            float cy = vh * 0.5f;
            float baseRadius = (8f + 4f * progress) * d;
            float thumbRadius = pressedDown ? 13.5f * d : baseRadius;

            if (pressedDown) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(withAlpha(checked ? PRIMARY : ON_SURFACE, 34));
                c.drawCircle(cx, cy, 19f * d, paint);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(blendColor(OUTLINE, ON_PRIMARY, progress));
            c.drawCircle(cx, cy, thumbRadius, paint);

            if (progress > 0.35f) {
                float checkAlpha = Math.min(1f, (progress - 0.35f) / 0.65f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.1f * d);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(withAlpha(PRIMARY_CONT, Math.round(255 * checkAlpha)));
                checkPath.reset();
                checkPath.moveTo(cx - 4.4f * d, cy + 0.2f * d);
                checkPath.lineTo(cx - 1.1f * d, cy + 3.4f * d);
                checkPath.lineTo(cx + 4.7f * d, cy - 3.2f * d);
                c.drawPath(checkPath, paint);
            }
        }
    }

    /** Material Design 3 Gen5W tile card with app icon, package/mod metadata, hidden badge & switch. */
    static final class TileCardView extends LinearLayout {
        final TileItem item;
        private final FrameLayout iconBox;
        private final TextView titleView;
        private final TextView hiddenBadge;
        private final TextView subView;
        private final M3Switch toggle;
        private boolean visibleState;

        TileCardView(Context c, final TileItem item, boolean initiallyVisible,
                     boolean showSubClass, final ToggleListener onVisibilityChange) {
            super(c);
            this.item = item;
            this.visibleState = initiallyVisible;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int pH = dp(c, 14), pV = dp(c, 11);
            setPadding(pH, pV, dp(c, 12), pV);

            iconBox = buildTileIconBox(c, item);
            addView(iconBox);

            LinearLayout mid = new LinearLayout(c);
            mid.setOrientation(VERTICAL);
            LayoutParams midLp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            midLp.leftMargin = dp(c, 12);
            midLp.rightMargin = dp(c, 10);

            LinearLayout topRow = new LinearLayout(c);
            topRow.setOrientation(HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            titleView = new TextView(c);
            titleView.setText(item.title);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            topRow.addView(titleView, new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView kindChip = pillBadge(c,
                    kindLabel(item),
                    item.isPackage() ? TERTIARY_CONT : SURFACE_HIGHEST,
                    item.isPackage() ? ON_TERTIARY_CONT : ON_SURFACE_VAR);
            LayoutParams kp = new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            kp.leftMargin = dp(c, 8);
            topRow.addView(kindChip, kp);

            hiddenBadge = pillBadge(c, "HIDDEN", WARN_CONT, ON_WARN_CONT);
            LayoutParams hp = new LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hp.leftMargin = dp(c, 6);
            topRow.addView(hiddenBadge, hp);
            mid.addView(topRow);

            subView = new TextView(c);
            subView.setText(buildSubtitle(item, showSubClass));
            subView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            subView.setSingleLine(true);
            subView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            LayoutParams sp = new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = dp(c, 3);
            mid.addView(subView, sp);

            addView(mid, midLp);

            toggle = new M3Switch(c, initiallyVisible, new ToggleListener() {
                @Override
                public void onToggle(boolean checked) {
                    applyVisualState(checked);
                    if (onVisibilityChange != null) onVisibilityChange.onToggle(checked);
                }
            });
            addView(toggle);

            setClickable(true);
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean next = !visibleState;
                    setTileVisible(next, true);
                    if (onVisibilityChange != null) onVisibilityChange.onToggle(next);
                }
            });

            applyVisualState(initiallyVisible);
        }

        void setTileVisible(boolean vis, boolean animate) {
            toggle.setChecked(vis, animate);
            applyVisualState(vis);
        }

        private void applyVisualState(boolean vis) {
            this.visibleState = vis;
            Context c = getContext();
            int strokePx = Math.max(1, dp(c, 1));
            int fill = vis ? SURFACE_CONT : SURFACE_LOW;
            int stroke = vis ? OUTLINE_VAR : withAlpha(WARN, night ? 105 : 130);
            setBackground(interactiveShape(fill, stroke, strokePx, dp(c, 16), STATE_ON_SURFACE));
            iconBox.setAlpha(vis ? 1f : 0.45f);
            titleView.setTextColor(vis ? ON_SURFACE : ON_SURFACE_VAR);
            subView.setTextColor(vis ? ON_SURFACE_VAR : OUTLINE);
            hiddenBadge.setVisibility(vis ? View.GONE : View.VISIBLE);
        }

        private static FrameLayout buildTileIconBox(Context c, TileItem item) {
            FrameLayout box = new FrameLayout(c);
            int bg = item.isPackage() ? withAlpha(TERTIARY_CONT, night ? 150 : 190) : SURFACE_HIGH;
            box.setBackground(shape(bg, OUTLINE_VAR, Math.max(1, dp(c, 1)), dp(c, 13)));
            box.setLayoutParams(new LayoutParams(dp(c, 44), dp(c, 44)));

            Drawable appIcon = loadInstalledIcon(c, item.packageName, item.className);
            if (appIcon != null) {
                ImageView iv = new ImageView(c);
                iv.setImageDrawable(appIcon);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                box.addView(iv, new FrameLayout.LayoutParams(dp(c, 32), dp(c, 32), Gravity.CENTER));
            } else {
                View vec = new View(c);
                vec.setBackground(new TileIcons(
                        item.isPackage() ? TileIcons.PACKAGE : TileIcons.SYSTEM,
                        item.isPackage() ? ON_TERTIARY_CONT : ON_SURFACE_VAR));
                box.addView(vec, new FrameLayout.LayoutParams(dp(c, 22), dp(c, 22), Gravity.CENTER));
            }
            return box;
        }

        private static Drawable loadInstalledIcon(Context c, String pkg, String cls) {
            if (pkg == null || pkg.isEmpty()) return null;
            PackageManager pm = c.getPackageManager();
            if (cls != null && !cls.isEmpty()) {
                String fullCls = cls.startsWith(".") ? pkg + cls : cls;
                try {
                    return pm.getActivityIcon(new ComponentName(pkg, fullCls));
                } catch (Throwable ignored) {}
            }
            try {
                return pm.getApplicationIcon(pkg);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static String kindLabel(TileItem item) {
            if (item.orphan) return "SAVED";
            if (item.isPackage()) {
                if ("hmgapi".equals(item.modId)) return "HMG-API";
                if ("nemesis".equals(item.modId)) return "NEMESIS";
                return "MOD";
            }
            return "SYSTEM";
        }

        private static String buildSubtitle(TileItem item, boolean showSubClass) {
            String base = item.packageName;
            if (item.modId != null && !item.modId.isEmpty()
                    && !"hmgapi".equals(item.modId) && !"nemesis".equals(item.modId)
                    && !item.modId.equals(item.packageName)) {
                base = item.modId + " · " + item.packageName;
            }
            if (showSubClass && item.className != null && !item.className.isEmpty()) {
                int dot = item.className.lastIndexOf('.');
                String shortCls = dot >= 0 ? item.className.substring(dot) : item.className;
                return base + " (" + shortCls + ")";
            }
            return base;
        }
    }

    static TextView pillBadge(Context c, String text, int fill, int fg) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(fg);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setSingleLine(true);
        tv.setPadding(dp(c, 7), dp(c, 2), dp(c, 7), dp(c, 2));
        tv.setBackground(shape(fill, 0, 0, dp(c, 8)));
        return tv;
    }

    static FrameLayout iconBadge(Context c, int iconKind, int bgCol, int iconCol, int boxDp, int iconDp) {
        FrameLayout box = new FrameLayout(c);
        box.setBackground(shape(bgCol, 0, 0, dp(c, boxDp * 0.34f)));
        View ic = new View(c);
        ic.setBackground(new TileIcons(iconKind, iconCol));
        box.addView(ic, new FrameLayout.LayoutParams(dp(c, iconDp), dp(c, iconDp), Gravity.CENTER));
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(c, boxDp), dp(c, boxDp)));
        return box;
    }

    static FrameLayout circleIconButton(Context c, int iconKind, int fill, int iconCol,
                                        int strokeCol, int sizeDp, View.OnClickListener click) {
        FrameLayout box = new FrameLayout(c);
        int strokePx = strokeCol != 0 ? Math.max(1, dp(c, 1)) : 0;
        box.setBackground(interactiveShape(fill, strokeCol, strokePx, dp(c, sizeDp * 0.5f), STATE_ON_SURFACE));
        View ic = new View(c);
        ic.setBackground(new TileIcons(iconKind, iconCol));
        int icPx = dp(c, Math.round(sizeDp * 0.50f));
        box.addView(ic, new FrameLayout.LayoutParams(icPx, icPx, Gravity.CENTER));
        box.setClickable(true);
        box.setOnClickListener(click);
        box.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return box;
    }

    static LinearLayout m3Button(Context c, int iconKind, String text, int style,
                                 int heightDp, View.OnClickListener click) {
        int fill, fg, stroke, stateCol;
        if (style == BTN_FILLED) {
            fill = PRIMARY;
            fg = ON_PRIMARY;
            stroke = 0;
            stateCol = STATE_ON_PRIMARY;
        } else if (style == BTN_OUTLINED) {
            fill = Color.TRANSPARENT;
            fg = ON_SURFACE;
            stroke = OUTLINE;
            stateCol = STATE_ON_SURFACE;
        } else {
            fill = SECONDARY_CONT;
            fg = ON_SECONDARY_CONT;
            stroke = 0;
            stateCol = STATE_ON_SURFACE;
        }

        LinearLayout btn = new LinearLayout(c);
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setGravity(Gravity.CENTER);
        int padL = dp(c, iconKind != 0 ? 13 : 16);
        int padR = dp(c, 16);
        btn.setPadding(padL, 0, padR, 0);
        btn.setMinimumHeight(dp(c, heightDp));
        int strokePx = stroke != 0 ? Math.max(1, dp(c, 1)) : 0;
        btn.setBackground(interactiveShape(fill, stroke, strokePx, dp(c, heightDp * 0.5f), stateCol));
        btn.setClickable(true);
        btn.setOnClickListener(click);

        if (iconKind != 0) {
            View ic = new View(c);
            ic.setBackground(new TileIcons(iconKind, fg));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(c, 17), dp(c, 17));
            ip.rightMargin = dp(c, 7);
            btn.addView(ic, ip);
        }

        TextView lbl = new TextView(c);
        lbl.setText(text);
        lbl.setTextColor(fg);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        lbl.setTypeface(Typeface.DEFAULT_BOLD);
        lbl.setSingleLine(true);
        btn.addView(lbl);
        return btn;
    }

    private TileWidgets() {}
}
