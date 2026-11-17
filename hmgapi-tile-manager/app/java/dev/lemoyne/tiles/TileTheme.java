package dev.lemoyne.tiles;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.StateSet;
import android.util.TypedValue;

/**
 * Material Design 3 color roles, shape tokens, and state-layer drawables for Home Screen Tile Manager.
 * Adapts automatically between head-unit Night (dark) and Day (light) modes via UiModeManager.
 *
 * Primary seed: Electric Gen5W Indigo-Blue (clarity & grid structure)
 * Tertiary seed: EV6 Cyan-Teal (HMG-API & NEMESIS mod packages)
 */
final class TileTheme {
    static boolean night = true;

    // M3 Surface container hierarchy (surface -> surfaceContainerHighest)
    static int SURFACE;
    static int SURFACE_LOW;
    static int SURFACE_CONT;
    static int SURFACE_HIGH;
    static int SURFACE_HIGHEST;

    // M3 Primary roles (Electric Gen5W Indigo-Blue)
    static int PRIMARY;
    static int ON_PRIMARY;
    static int PRIMARY_CONT;
    static int ON_PRIMARY_CONT;

    // M3 Secondary roles (Navigation rail active indicator & tonal buttons)
    static int SECONDARY_CONT;
    static int ON_SECONDARY_CONT;

    // M3 Tertiary roles (Cyan-Teal Mod Package highlights)
    static int TERTIARY;
    static int ON_TERTIARY;
    static int TERTIARY_CONT;
    static int ON_TERTIARY_CONT;

    // Semantic status roles (Connected / Hidden warning)
    static int OK;
    static int OK_CONT;
    static int ON_OK_CONT;
    static int WARN;
    static int WARN_CONT;
    static int ON_WARN_CONT;

    // M3 Content, Outline & State Layer roles
    static int ON_SURFACE;
    static int ON_SURFACE_VAR;
    static int OUTLINE;
    static int OUTLINE_VAR;
    static int STATE_ON_SURFACE;
    static int STATE_ON_PRIMARY;

    static void apply(Context c) {
        int mask = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        night = (mask != Configuration.UI_MODE_NIGHT_NO);
        if (night) {
            SURFACE = 0xFF10141A;
            SURFACE_LOW = 0xFF171C24;
            SURFACE_CONT = 0xFF1D232D;
            SURFACE_HIGH = 0xFF262D39;
            SURFACE_HIGHEST = 0xFF313947;

            PRIMARY = 0xFF9ACBFF;
            ON_PRIMARY = 0xFF003355;
            PRIMARY_CONT = 0xFF0E4A75;
            ON_PRIMARY_CONT = 0xFFCFE5FF;

            SECONDARY_CONT = 0xFF2E3F54;
            ON_SECONDARY_CONT = 0xFFD4E4F8;

            TERTIARY = 0xFF66D8D0;
            ON_TERTIARY = 0xFF003734;
            TERTIARY_CONT = 0xFF00504C;
            ON_TERTIARY_CONT = 0xFF9CF4ED;

            OK = 0xFF8BD69E;
            OK_CONT = 0xFF1B4D2B;
            ON_OK_CONT = 0xFFB6F2C4;

            WARN = 0xFFFFB86C;
            WARN_CONT = 0xFF5D3800;
            ON_WARN_CONT = 0xFFFFDDBB;

            ON_SURFACE = 0xFFE1E6EF;
            ON_SURFACE_VAR = 0xFFBEC6D4;
            OUTLINE = 0xFF8891A0;
            OUTLINE_VAR = 0xFF3F4754;
            STATE_ON_SURFACE = 0x22E1E6EF;
            STATE_ON_PRIMARY = 0x24003355;
        } else {
            SURFACE = 0xFFF7F9FE;
            SURFACE_LOW = 0xFFF0F4FA;
            SURFACE_CONT = 0xFFEAEFF6;
            SURFACE_HIGH = 0xFFE3E8F0;
            SURFACE_HIGHEST = 0xFFDCE2EB;

            PRIMARY = 0xFF176096;
            ON_PRIMARY = 0xFFFFFFFF;
            PRIMARY_CONT = 0xFFCFE5FF;
            ON_PRIMARY_CONT = 0xFF001D34;

            SECONDARY_CONT = 0xFFD4E4F8;
            ON_SECONDARY_CONT = 0xFF0E1D2D;

            TERTIARY = 0xFF006A65;
            ON_TERTIARY = 0xFFFFFFFF;
            TERTIARY_CONT = 0xFF9CF4ED;
            ON_TERTIARY_CONT = 0xFF00201E;

            OK = 0xFF1E6C35;
            OK_CONT = 0xFFB6F2C4;
            ON_OK_CONT = 0xFF00210A;

            WARN = 0xFF885100;
            WARN_CONT = 0xFFFFDDBB;
            ON_WARN_CONT = 0xFF2B1700;

            ON_SURFACE = 0xFF181C22;
            ON_SURFACE_VAR = 0xFF414751;
            OUTLINE = 0xFF717882;
            OUTLINE_VAR = 0xFFC1C7D2;
            STATE_ON_SURFACE = 0x1F181C22;
            STATE_ON_PRIMARY = 0x24FFFFFF;
        }
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    static GradientDrawable shape(int fill, int stroke, int strokePx, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0 && stroke != 0) {
            g.setStroke(strokePx, stroke);
        }
        return g;
    }

    /** M3 interactive container with a pressed/focused state-layer overlay (API 19 compatible). */
    static Drawable interactiveShape(int fill, int stroke, int strokePx, float radiusPx, int stateLayerColor) {
        GradientDrawable normal = shape(fill, stroke, strokePx, radiusPx);
        GradientDrawable pressedBase = shape(fill, stroke, strokePx, radiusPx);
        GradientDrawable overlay = shape(stateLayerColor, 0, 0, radiusPx);
        LayerDrawable pressed = new LayerDrawable(new Drawable[] { pressedBase, overlay });

        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[] { android.R.attr.state_pressed }, pressed);
        sld.addState(new int[] { android.R.attr.state_focused }, pressed);
        sld.addState(StateSet.WILD_CARD, normal);
        return sld;
    }

    private TileTheme() {}
}
