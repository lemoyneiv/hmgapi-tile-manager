package dev.lemoyne.tiles;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Resolves tile icons for both custom Packages/Mods and stock Gen5W System Apps:
 * - Custom packages/mods use their Activity/Application icon (or HMG-API mod icon PNG),
 *   rejecting Android's generic robot placeholder (sym_def_app_icon).
 * - Stock Gen5W system apps never query the target system APK's stub/missing manifest icon.
 *   Instead they resolve in three layers:
 *     1. Exact R.drawable.* / R.mipmap.* captured by TileModEntry inside Launcher_WP3.
 *     2. Direct drawable table lookup in com.android.launcher3 & com.hkmc.system.app.homesetting
 *        (so genuine stock icons appear immediately even before rebooting onto a newer hook).
 *     3. Crisp Gen5W cyan-to-purple gradient vector fallback matching Launcher_WP3's palette.
 */
final class SystemIconResolver {
    private static final String PKG_LAUNCHER = "com.android.launcher3";
    private static final String PKG_HOMESETTING = "com.hkmc.system.app.homesetting";
    private static final File MODS_ROOT = new File("/data/hmgapi/mods");

    private static final Object CACHE_LOCK = new Object();
    private static Map<String, Integer> launcherDrawables;
    private static Map<String, Integer> homeSettingDrawables;

    static final class ResolvedIcon {
        final Drawable drawable;
        final boolean isLauncherPlate;
        final boolean isVector;

        ResolvedIcon(Drawable drawable, boolean isLauncherPlate, boolean isVector) {
            this.drawable = drawable;
            this.isLauncherPlate = isLauncherPlate;
            this.isVector = isVector;
        }
    }

    private static final class FeatureRule {
        final String[] requiredAny;
        final String[] excludedAny;
        final int fallbackVectorKind;

        FeatureRule(int fallbackVectorKind, String[] requiredAny, String[] excludedAny) {
            this.fallbackVectorKind = fallbackVectorKind;
            this.requiredAny = requiredAny;
            this.excludedAny = excludedAny;
        }
    }

    static ResolvedIcon resolve(Context c, TileWidgets.TileItem item) {
        if (item.isPackage()) {
            return resolvePackageIcon(c, item);
        }
        return resolveSystemIcon(c, item);
    }

    private static ResolvedIcon resolvePackageIcon(Context c, TileWidgets.TileItem item) {
        String pkg = item.packageName;
        PackageManager pm = c.getPackageManager();

        // 1. Prefer HMG-API mod launcher PNG if an APK lacks its own manifest icon, otherwise
        //    keep exact Activity/Application icon behavior for custom apps.
        if (pkg != null && !pkg.isEmpty()) {
            String cls = item.className;
            if (cls != null && !cls.isEmpty() && !"*".equals(cls)) {
                String fullCls = cls.startsWith(".") ? pkg + cls : cls;
                try {
                    ComponentName cn = new ComponentName(pkg, fullCls);
                    ActivityInfo ai = pm.getActivityInfo(cn, 0);
                    int iconRes = ai.getIconResource();
                    if (iconRes != 0 && iconRes != android.R.drawable.sym_def_app_icon) {
                        Drawable d = freshDrawable(c.getResources(), ai.loadIcon(pm));
                        if (d != null) return new ResolvedIcon(d, false, false);
                    }
                } catch (Throwable ignored) {}
            }
            try {
                ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                if (appInfo.icon != 0 && appInfo.icon != android.R.drawable.sym_def_app_icon) {
                    Drawable d = freshDrawable(c.getResources(), appInfo.loadIcon(pm));
                    if (d != null) return new ResolvedIcon(d, false, false);
                }
            } catch (Throwable ignored) {}
        }

        Drawable modPng = loadModLauncherPng(c, item);
        if (modPng != null) {
            return new ResolvedIcon(modPng, true, false);
        }

        return new ResolvedIcon(
                new TileIcons(TileIcons.PACKAGE, TileTheme.ON_TERTIARY_CONT), false, true);
    }

    private static ResolvedIcon resolveSystemIcon(Context c, TileWidgets.TileItem item) {
        // Layer 1: Exact drawable resource captured from Launcher_WP3's menu entry (Q)
        if ((item.iconName != null && !item.iconName.isEmpty()) || item.iconId != 0) {
            String targetPkg = (item.iconPkg != null && !item.iconPkg.isEmpty())
                    ? item.iconPkg : PKG_LAUNCHER;
            Resources res = getThemedResources(c, targetPkg);
            if (res != null) {
                int rid = 0;
                if (item.iconName != null && !item.iconName.isEmpty()) {
                    String defType = (item.iconType != null && !item.iconType.isEmpty())
                            ? item.iconType : "drawable";
                    try {
                        rid = res.getIdentifier(item.iconName, defType, targetPkg);
                    } catch (Throwable ignored) {}
                }
                if (rid == 0 && item.iconId != 0 && (item.iconId & 0xffff0000) != 0x7f7e0000) {
                    rid = item.iconId;
                }
                Drawable d = loadValidTileDrawable(c, res, rid, false);
                if (d != null) {
                    return new ResolvedIcon(d, true, false);
                }
            }
        }

        // Layer 2: Direct drawable table scan of Launcher_WP3 & HKMC_HomeSetting_WP
        FeatureRule rule = classifySystemFeature(item);
        if (rule.requiredAny != null && rule.requiredAny.length > 0) {
            Drawable fromLauncher = findBestLauncherDrawable(c, PKG_LAUNCHER, rule);
            if (fromLauncher != null) {
                return new ResolvedIcon(fromLauncher, true, false);
            }
            Drawable fromHomeSetting = findBestLauncherDrawable(c, PKG_HOMESETTING, rule);
            if (fromHomeSetting != null) {
                return new ResolvedIcon(fromHomeSetting, true, false);
            }
        }

        // Layer 3: Crisp Gen5W cyan-to-purple gradient vector fallback
        return new ResolvedIcon(TileIcons.stockSystem(rule.fallbackVectorKind), false, true);
    }

    private static Drawable findBestLauncherDrawable(Context c, String hostPkg, FeatureRule rule) {
        Resources res = getThemedResources(c, hostPkg);
        if (res == null) return null;
        Map<String, Integer> table = getDrawableTable(res, hostPkg);
        if (table.isEmpty()) return null;

        int bestScore = Integer.MIN_VALUE;
        int bestId = 0;
        int secondScore = Integer.MIN_VALUE;
        int secondId = 0;

        for (Map.Entry<String, Integer> e : table.entrySet()) {
            String name = e.getKey();
            int score = scoreCandidateName(name, rule);
            if (score <= 0) continue;
            int id = e.getValue().intValue();
            if (score > bestScore) {
                secondScore = bestScore;
                secondId = bestId;
                bestScore = score;
                bestId = id;
            } else if (score > secondScore) {
                secondScore = score;
                secondId = id;
            }
        }

        if (bestId != 0) {
            Drawable d = loadValidTileDrawable(c, res, bestId, true);
            if (d != null) return d;
        }
        if (secondId != 0) {
            Drawable d = loadValidTileDrawable(c, res, secondId, true);
            if (d != null) return d;
        }
        return null;
    }

    static String bestMatchingName(Iterable<String> candidateNames, TileWidgets.TileItem item) {
        FeatureRule rule = classifySystemFeature(item);
        if (rule.requiredAny == null || rule.requiredAny.length == 0) return null;
        int bestScore = Integer.MIN_VALUE;
        String bestName = null;
        for (String name : candidateNames) {
            int s = scoreCandidateName(name, rule);
            if (s > 0 && s > bestScore) {
                bestScore = s;
                bestName = name;
            }
        }
        return bestName;
    }

    static int scoreCandidateName(String rawName, FeatureRule rule) {
        if (rawName == null || rawName.isEmpty()) return -1;
        String low = rawName.toLowerCase(Locale.US);
        String[] tokens = low.split("_");
        if (tokens.length == 0) return -1;

        // Must carry a Home Screen / All-Menu icon prefix or Launcher_WP3's *_66 tile marker
        int prefixScore = 0;
        if (low.startsWith("hom_")) {
            prefixScore = 120;
        } else if (low.startsWith("home_") || low.startsWith("ico_home_") || low.startsWith("ic_home_")) {
            prefixScore = 95;
        } else if (low.startsWith("allmenu_") || low.startsWith("all_menu_")
                || low.startsWith("mainmenu_") || low.startsWith("main_menu_")
                || low.startsWith("menu_ico_") || low.startsWith("ico_menu_")
                || low.startsWith("ic_menu_") || low.startsWith("btn_home_")) {
            prefixScore = 75;
        } else if (hasToken(tokens, "66")) {
            prefixScore = 65;
        } else {
            return -1;
        }

        // Reject disabled, pressed, focused, widget, badge, split-screen, or UI chrome assets
        if (low.endsWith("_d") || low.endsWith("_p") || low.endsWith("_f") || low.endsWith("_s")) {
            return -1;
        }
        for (String tok : tokens) {
            if (isRejectedToken(tok)) return -1;
        }

        // Reject feature-specific exclusions (e.g. "hd" on Radio, "projection" on Phone)
        String joined = low.replace("_", "");
        if (rule.excludedAny != null) {
            for (String ex : rule.excludedAny) {
                if (hasToken(tokens, ex) || (ex.length() >= 4 && joined.contains(ex))) {
                    return -1;
                }
            }
        }

        // Match required semantic feature token(s)
        int matchBonus = 0;
        for (int i = 0; i < rule.requiredAny.length; i++) {
            String req = rule.requiredAny[i];
            int priorityBonus = Math.max(0, 16 - i * 2);
            if (hasToken(tokens, req)) {
                matchBonus = Math.max(matchBonus, 60 + priorityBonus);
            } else if (req.length() >= 4 && joined.contains(req)) {
                matchBonus = Math.max(matchBonus, 35 + priorityBonus);
            }
        }
        if (matchBonus == 0) return -1;

        int score = prefixScore + matchBonus;
        if (hasToken(tokens, "66")) score += 45;
        if (low.endsWith("_n") || low.endsWith("_nor") || low.endsWith("_normal")
                || low.endsWith("_def") || low.endsWith("_default")) {
            score += 20;
        }
        score -= tokens.length * 3;
        return score;
    }

    private static boolean hasToken(String[] tokens, String target) {
        for (String t : tokens) {
            if (target.equals(t)) return true;
        }
        return false;
    }

    private static boolean isRejectedToken(String tok) {
        if (tok == null || tok.isEmpty()) return false;
        return "dis".equals(tok) || "disabled".equals(tok) || "disable".equals(tok)
                || "dim".equals(tok) || "dimmed".equals(tok) || "off".equals(tok)
                || "inactive".equals(tok) || "lock".equals(tok) || "locked".equals(tok)
                || "prs".equals(tok) || "press".equals(tok) || "pressed".equals(tok)
                || "push".equals(tok) || "down".equals(tok)
                || "foc".equals(tok) || "focus".equals(tok) || "focused".equals(tok)
                || "sel".equals(tok) || "select".equals(tok) || "selected".equals(tok)
                || "chk".equals(tok) || "check".equals(tok) || "hover".equals(tok)
                || "bg".equals(tok) || "back".equals(tok) || "background".equals(tok)
                || "plate".equals(tok) || "mask".equals(tok) || "shadow".equals(tok)
                || "glow".equals(tok) || "frame".equals(tok) || "border".equals(tok)
                || "line".equals(tok) || "div".equals(tok) || "divider".equals(tok)
                || "bar".equals(tok) || "statusbar".equals(tok) || "tab".equals(tok)
                || "dot".equals(tok) || "ind".equals(tok) || "indicator".equals(tok)
                || "page".equals(tok) || "badge".equals(tok) || "count".equals(tok)
                || "split".equals(tok) || "widget".equals(tok) || "wgt".equals(tok)
                || "dock".equals(tok) || "edit".equals(tok) || "move".equals(tok)
                || "drag".equals(tok) || "del".equals(tok) || "delete".equals(tok)
                || "remove".equals(tok) || "close".equals(tok) || "arrow".equals(tok)
                || "loading".equals(tok) || "spinner".equals(tok) || "empty".equals(tok)
                || "dummy".equals(tok) || "sample".equals(tok) || "preview".equals(tok)
                || "thumb".equals(tok) || "popup".equals(tok) || "toast".equals(tok)
                || "alert".equals(tok) || "small".equals(tok) || "mini".equals(tok)
                || "24".equals(tok) || "32".equals(tok);
    }

    private static FeatureRule classifySystemFeature(TileWidgets.TileItem item) {
        String title = (item.title == null ? "" : item.title).toLowerCase(Locale.US);
        String pkg = (item.packageName == null ? "" : item.packageName).toLowerCase(Locale.US);
        String cls = (item.className == null ? "" : item.className).toLowerCase(Locale.US);
        String all = title + " | " + pkg + " | " + cls;

        // 1. Phone Projection / Android Auto / CarPlay (must precede Phone!)
        if (all.contains("projection") || all.contains("carplay") || all.contains("android auto")
                || all.contains("androidauto") || all.contains("mirrorlink") || all.contains("carlife")
                || pkg.contains("connectivity")) {
            return new FeatureRule(TileIcons.SYS_PROJECTION,
                    new String[] { "projection", "phoneprojection", "connectivity", "carplay", "mirrorlink", "carlife" },
                    null);
        }

        // 2. HD Radio / Traffic Data (must precede Radio!)
        if (title.contains("hd radio") || all.contains("hdradio") || all.contains("hddata")
                || all.contains("hd_radio") || all.contains("dmb")) {
            return new FeatureRule(TileIcons.SYS_HD_RADIO,
                    new String[] { "hdradio", "hddata", "dmb" },
                    null);
        }

        // 3. SiriusXM (must precede Radio!)
        if (all.contains("sirius") || all.contains("sxm")) {
            return new FeatureRule(TileIcons.SYS_RADIO,
                    new String[] { "sxm", "sirius", "siriusxm" },
                    null);
        }

        // 4. Powertrain: PHEV / HEV / FCEV / EV
        if (all.contains("phev") || title.contains("plug-in")) {
            return new FeatureRule(TileIcons.SYS_EV,
                    new String[] { "phev", "hev", "ev" }, null);
        }
        if (all.contains("hev") || title.contains("hybrid")) {
            return new FeatureRule(TileIcons.SYS_EV,
                    new String[] { "hev", "hybrid", "ev" }, new String[] { "phev" });
        }
        if (all.contains("fcev") || title.contains("fuel cell") || title.contains("hydrogen")) {
            return new FeatureRule(TileIcons.SYS_EV,
                    new String[] { "fcev", "ev" }, null);
        }
        if ("ev".equals(title.trim()) || title.startsWith("ev ") || pkg.endsWith(".ev")
                || pkg.contains(".ev.") || cls.contains("evactivity") || cls.contains("evmain")) {
            return new FeatureRule(TileIcons.SYS_EV,
                    new String[] { "ev" }, new String[] { "hev", "phev", "fcev" });
        }

        // 5. Map vs Navigation Menu
        if ("map".equals(title.trim()) || cls.endsWith(".map") || cls.contains("mapactivity")) {
            return new FeatureRule(TileIcons.SYS_MAP,
                    new String[] { "map", "mapview" }, new String[] { "navi", "navimenu" });
        }
        if (title.contains("navigation") || title.contains("navi") || pkg.contains("mnsoft.navi")
                || pkg.contains(".navi") || cls.contains("navi")) {
            return new FeatureRule(TileIcons.SYS_NAVI,
                    new String[] { "navi", "navimenu", "navigation", "search" },
                    new String[] { "map" });
        }

        // 6. Phone / Bluetooth Handsfree
        if (title.contains("phone") || pkg.contains("phone") || pkg.contains("btphone")
                || cls.contains("phone") || cls.contains("dial")) {
            return new FeatureRule(TileIcons.SYS_PHONE,
                    new String[] { "phone", "btphone", "call", "bt" },
                    new String[] { "projection", "phoneprojection", "carplay" });
        }

        // 7. Radio
        if (title.contains("radio") || pkg.contains("radio") || "radio".equals(cls)
                || cls.contains("radio")) {
            return new FeatureRule(TileIcons.SYS_RADIO,
                    new String[] { "radio", "dab", "fm" },
                    new String[] { "hd", "hdradio", "hddata", "sxm", "sirius" });
        }

        // 8. Voice Memo
        if (title.contains("voice memo") || all.contains("voicememo") || title.contains("memo")
                || pkg.contains("memo") || pkg.contains("recorder")) {
            return new FeatureRule(TileIcons.SYS_VOICE_MEMO,
                    new String[] { "voicememo", "memo", "voice", "recorder", "rec" },
                    null);
        }

        // 9. Passenger View / Cabin Camera vs Passenger Talk vs Built-in Cam (DVRS)
        if (title.contains("passenger view") || all.contains("passengerview")
                || all.contains("cabinview") || all.contains("cabincam")) {
            return new FeatureRule(TileIcons.SYS_CAMERA,
                    new String[] { "passengerview", "cabinview", "cabincam" },
                    new String[] { "talk", "passengertalk" });
        }
        if (title.contains("passenger talk") || title.contains("driver talk")
                || all.contains("passengertalk") || all.contains("drivertalk")) {
            return new FeatureRule(TileIcons.SYS_PASSENGER_TALK,
                    new String[] { "passengertalk", "drivertalk", "talk", "intercom" },
                    new String[] { "view", "passengerview", "cam" });
        }
        if (title.contains("built-in cam") || all.contains("dvrs") || all.contains("builtincam")
                || all.contains("blackbox") || all.contains("dashcam")) {
            return new FeatureRule(TileIcons.SYS_CAMERA,
                    new String[] { "dvrs", "builtincam", "blackbox", "cam" },
                    new String[] { "passenger", "passengerview" });
        }
        if (title.contains("rear view") || title.contains("driving rear")
                || all.contains("drvm") || pkg.contains("rearcamera")) {
            return new FeatureRule(TileIcons.SYS_CAMERA,
                    new String[] { "drvm", "rearcam", "rearcamera", "rearview" },
                    new String[] { "passenger", "talk", "dvrs" });
        }

        // 10. Quiet Mode / Rear Sleep Mode
        if (title.contains("quiet") || title.contains("sleep") || all.contains("quietmode")
                || all.contains("sleepmode") || all.contains("rearsleep")) {
            return new FeatureRule(TileIcons.SYS_QUIET,
                    new String[] { "quiet", "quietmode", "sleep", "sleepmode", "rearsleep" },
                    null);
        }

        // 11. Valet Mode
        if (title.contains("valet") || all.contains("valetmode")) {
            return new FeatureRule(TileIcons.SYS_VALET,
                    new String[] { "valet", "valetmode" },
                    null);
        }

        // 12. User's Manual / Web Manual / QR Manual
        if (title.contains("manual") || all.contains("qrmanual") || all.contains("webmanual")
                || title.contains("owner's guide")) {
            return new FeatureRule(TileIcons.SYS_MANUAL,
                    new String[] { "qrmanual", "manual", "webmanual", "qr", "guide" },
                    null);
        }

        // 13. Hyundai Pay / Kia Pay / Genesis Pay / CarPay
        if (title.contains("pay") || all.contains("carpay") || all.contains("icarpay")) {
            return new FeatureRule(TileIcons.SYS_PAY,
                    new String[] { "carpay", "icarpay", "pay", "hpay", "kpay", "gpay" },
                    null);
        }

        // 14. Connected Car: Bluelink / Kia Connect / UVO / Genesis Connected Services
        if (all.contains("bluelink") || title.contains("blue link")) {
            return new FeatureRule(TileIcons.SYS_CONNECT,
                    new String[] { "bluelink", "tms", "telematics", "connected" },
                    null);
        }
        if (all.contains("uvo") || title.contains("kia connect") || all.contains("kiaconnect")) {
            return new FeatureRule(TileIcons.SYS_CONNECT,
                    new String[] { "kiaconnect", "uvo", "uvoconnect", "connect", "tms", "telematics" },
                    null);
        }
        if (all.contains("gcs") || title.contains("connected services") || all.contains("telematics")) {
            return new FeatureRule(TileIcons.SYS_CONNECT,
                    new String[] { "gcs", "tms", "telematics", "bluelink", "uvo", "connect" },
                    null);
        }

        // 15. Notifications / Notice Center
        if (title.contains("notification") || title.contains("notice")
                || pkg.contains("notification") || cls.contains("notice")) {
            return new FeatureRule(TileIcons.SYS_NOTIFICATIONS,
                    new String[] { "noti", "notification", "notifications", "notice", "noticenter" },
                    null);
        }

        // 16. Climate / A/C / Seat Warmer
        if (title.contains("climate") || title.contains("air ") || all.contains("aircon")
                || all.contains("hvac") || all.contains("warmer")) {
            return new FeatureRule(TileIcons.SYS_CLIMATE,
                    new String[] { "climate", "aircon", "hvac", "ac", "warmer", "seatwarmer" },
                    null);
        }

        // 17. Weather
        if (title.contains("weather") || pkg.contains("weather")) {
            return new FeatureRule(TileIcons.SYS_WEATHER,
                    new String[] { "weather" },
                    null);
        }

        // 18. Sound Mood Lamp / Sounds of Nature / Media / Music
        if (title.contains("mood lamp") || all.contains("moodlamp") || all.contains("sml")) {
            return new FeatureRule(TileIcons.SYS_MEDIA,
                    new String[] { "sml", "moodlamp", "soundmood", "mood" },
                    null);
        }
        if (title.contains("nature") || all.contains("soundofnature")) {
            return new FeatureRule(TileIcons.SYS_MEDIA,
                    new String[] { "nature", "soundofnature", "son" },
                    null);
        }
        if (title.contains("media") || title.contains("music") || title.contains("vibe")
                || title.contains("melon") || title.contains("genie") || title.contains("audio")
                || pkg.contains("media") || pkg.contains("music") || pkg.contains("vibe")
                || pkg.contains("melon") || pkg.contains("genie") || pkg.contains("soundhound")
                || pkg.contains("aharadio") || "media".equals(cls)) {
            return new FeatureRule(TileIcons.SYS_MEDIA,
                    new String[] { "media", "music", "av", "audio", "vibe", "melon", "genie" },
                    null);
        }

        // 19. Memory Seat Configuration (IMS) vs Setup / Settings
        if (title.contains("memory seat") || all.contains("imsseat") || cls.contains("ims")) {
            return new FeatureRule(TileIcons.SYS_SETUP,
                    new String[] { "ims", "memoryseat", "seat" },
                    new String[] { "warmer" });
        }
        if (title.contains("setup") || title.contains("setting") || "com.android.settings".equals(pkg)) {
            return new FeatureRule(TileIcons.SYS_SETUP,
                    new String[] { "setting", "settings", "setup", "config" },
                    new String[] { "ims", "seat", "sml", "mood" });
        }

        // 20. Sports / Calendar / Compass
        if (title.contains("sport") || pkg.contains("sport")) {
            return new FeatureRule(TileIcons.SYSTEM, new String[] { "sports", "sport" }, null);
        }
        if (title.contains("calendar") || pkg.contains("calendar")) {
            return new FeatureRule(TileIcons.SYSTEM, new String[] { "calendar", "schedule" }, null);
        }

        return new FeatureRule(TileIcons.SYSTEM, null, null);
    }

    @SuppressWarnings("deprecation")
    private static Resources getThemedResources(Context c, String pkg) {
        try {
            Resources appRes = c.getResources();
            Resources res = c.getPackageManager().getResourcesForApplication(pkg);
            Configuration cfg = new Configuration(appRes.getConfiguration());
            res.updateConfiguration(cfg, appRes.getDisplayMetrics());
            return res;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Map<String, Integer> getDrawableTable(Resources res, String hostPkg) {
        synchronized (CACHE_LOCK) {
            if (PKG_LAUNCHER.equals(hostPkg) && launcherDrawables != null) {
                return launcherDrawables;
            }
            if (PKG_HOMESETTING.equals(hostPkg) && homeSettingDrawables != null) {
                return homeSettingDrawables;
            }
        }

        Map<String, Integer> scanned = scanDrawableEntries(res);
        synchronized (CACHE_LOCK) {
            if (PKG_LAUNCHER.equals(hostPkg)) {
                launcherDrawables = scanned;
            } else if (PKG_HOMESETTING.equals(hostPkg)) {
                homeSettingDrawables = scanned;
            }
        }
        return scanned;
    }

    private static Map<String, Integer> scanDrawableEntries(Resources res) {
        if (res == null) return Collections.emptyMap();
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (int type = 1; type <= 20; type++) {
            String typeName = null;
            for (int probe = 0; probe <= 2 && typeName == null; probe++) {
                try {
                    typeName = res.getResourceTypeName(0x7f000000 | (type << 16) | probe);
                } catch (Throwable ignored) {}
            }
            if (!"drawable".equals(typeName) && !"mipmap".equals(typeName)) continue;

            int misses = 0;
            for (int entry = 0; entry < 8192; entry++) {
                int id = 0x7f000000 | (type << 16) | entry;
                try {
                    String name = res.getResourceEntryName(id);
                    if (name != null && !name.isEmpty()) {
                        String low = name.toLowerCase(Locale.US);
                        if (!out.containsKey(low)) {
                            out.put(low, Integer.valueOf(id));
                        }
                        misses = 0;
                    } else {
                        if (++misses > 64) break;
                    }
                } catch (Throwable ignored) {
                    if (++misses > 64) break;
                }
            }
        }
        return out;
    }

    @SuppressWarnings("deprecation")
    private static Drawable loadValidTileDrawable(Context c, Resources res, int rid, boolean enforceSquareBounds) {
        if (res == null || rid == 0 || rid == android.R.drawable.sym_def_app_icon) return null;
        try {
            Drawable d = res.getDrawable(rid);
            if (d == null) return null;
            int w = d.getIntrinsicWidth();
            int h = d.getIntrinsicHeight();
            if (enforceSquareBounds) {
                if (w < 36 || w > 280 || h < 36 || h > 280) return null;
                if (Math.abs(w - h) > Math.max(w, h) * 0.25f) return null;
            } else if (w <= 0 || h <= 0) {
                return null;
            }
            return freshDrawable(c.getResources(), d);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Drawable freshDrawable(Resources hostRes, Drawable d) {
        if (d == null) return null;
        try {
            Drawable.ConstantState cs = d.getConstantState();
            if (cs != null) {
                return cs.newDrawable(hostRes);
            }
        } catch (Throwable ignored) {}
        return d;
    }

    private static Drawable loadModLauncherPng(Context c, TileWidgets.TileItem item) {
        if (item.modId == null || item.modId.isEmpty()
                || "hmgapi".equals(item.modId) || "nemesis".equals(item.modId)) {
            return null;
        }
        File modDir = new File(MODS_ROOT, item.modId);
        if (!modDir.isDirectory()) return null;

        String iconRel = null;
        File mjFile = new File(modDir, "mod.json");
        if (mjFile.isFile() && mjFile.canRead()) {
            try {
                JSONObject mj = new JSONObject(readUtf8(mjFile));
                JSONObject prov = mj.optJSONObject("provides");
                JSONArray arr = prov != null ? prov.optJSONArray("launcher") : null;
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject lm = arr.optJSONObject(i);
                        if (lm == null) continue;
                        if (item.packageName.equals(lm.optString("package", ""))) {
                            iconRel = TileTheme.night
                                    ? lm.optString("icon_night", lm.optString("icon", ""))
                                    : lm.optString("icon", lm.optString("icon_night", ""));
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (iconRel == null || iconRel.isEmpty()) {
            iconRel = TileTheme.night ? "icons/icon_night.png" : "icons/icon.png";
        }
        File iconFile = new File(modDir, iconRel);
        if (!iconFile.isFile()) {
            iconFile = new File(modDir, "icons/icon.png");
        }
        if (!iconFile.isFile() || !iconFile.canRead()) return null;

        try {
            Bitmap bmp = BitmapFactory.decodeFile(iconFile.getAbsolutePath());
            if (bmp != null) return new BitmapDrawable(c.getResources(), bmp);
        } catch (Throwable ignored) {}
        return null;
    }

    private static String readUtf8(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private SystemIconResolver() {}
}
