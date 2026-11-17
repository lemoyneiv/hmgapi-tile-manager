package dev.lemoyne.tiles;

import static dev.lemoyne.tiles.TileTheme.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import hmgapi.client.HmgApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Material Design 3 widescreen automotive interface for Home Screen Tile Manager (1920x720 Gen5W):
 *  - Left M3 Navigation & Summary Rail: HMG-API status, Visible/Total hero card, Close Empty Gaps
 *    switch, and 3 category destinations (All Tiles, Packages & Mods, System Apps) with count badges
 *  - Right Contextual Pane: 2-column touch-friendly tile grid, Refresh & Show All actions, safety
 *    confirmation before hiding critical management apps, and instant live launcher/editor sync
 */
public final class MainActivity extends Activity {
    private static final int TAB_ALL = 0;
    private static final int TAB_PACKAGES = 1;
    private static final int TAB_SYSTEM = 2;

    private static int activeTab = TAB_ALL;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private final List<TileWidgets.TileItem> catalogItems = new ArrayList<TileWidgets.TileItem>();
    private final Set<String> hiddenKeys = new LinkedHashSet<String>();
    private boolean compactGaps = true;
    private boolean hmgApiConnected;
    private int saveGeneration;

    // Left rail views
    private TextView lblConnSub;
    private TextView lblHeroCount;
    private TextView lblHeroChip;
    private TileWidgets.M3Switch swCompactGaps;
    private final LinearLayout[] navItems = new LinearLayout[3];
    private final TileIcons[] navIcons = new TileIcons[3];
    private final TextView[] navLabels = new TextView[3];
    private final TextView[] navBadges = new TextView[3];

    // Right pane views
    private TextView pageTitle;
    private TextView pageSubtitle;
    private TextView lblSaveStatus;
    private LinearLayout btnShowAll;
    private ScrollView gridScroll;
    private LinearLayout gridContainer;

    private final Runnable providerCatalogCallback = new Runnable() {
        @Override
        public void run() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing()) reloadFromDisk(false);
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        TileTheme.apply(this);
        setTheme(TileTheme.night
                ? android.R.style.Theme_Holo_NoActionBar
                : android.R.style.Theme_Holo_Light_NoActionBar);
        super.onCreate(savedInstanceState);
        setContentView(buildRoot());
        selectTab(activeTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        TileCatalogProvider.setCatalogListener(providerCatalogCallback);
        reloadFromDisk(true);
    }

    @Override
    protected void onPause() {
        TileCatalogProvider.setCatalogListener(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        TileTheme.apply(this);
        setContentView(buildRoot());
        selectTab(activeTab);
        renderAll();
    }

    // ---- Root M3 Widescreen Layout (Left Rail + Right Contextual Pane) ----

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(SURFACE);
        // 38dp right padding keeps controls clear of the Gen5W split-screen drawer handle
        root.setPadding(d(14), d(8), d(38), d(8));

        root.addView(buildLeftRail(), new LinearLayout.LayoutParams(d(316), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.addView(buildRightHeader());

        gridScroll = new ScrollView(this);
        gridScroll.setFillViewport(true);
        gridScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        gridContainer = new LinearLayout(this);
        gridContainer.setOrientation(LinearLayout.VERTICAL);
        gridContainer.setPadding(0, d(2), 0, d(12));
        gridScroll.addView(gridContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        stageLp.topMargin = d(10);
        right.addView(gridScroll, stageLp);

        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rightLp.leftMargin = d(14);
        root.addView(right, rightLp);
        return root;
    }

    private View buildLeftRail() {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setBackground(shape(SURFACE_LOW, OUTLINE_VAR, Math.max(1, d(1)), d(22)));
        int pad = d(14);
        rail.setPadding(pad, d(13), pad, d(13));

        // 1. Brand row
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        brand.addView(TileWidgets.iconBadge(this, TileIcons.GRID_ALL, PRIMARY_CONT, ON_PRIMARY_CONT, 40, 21));

        LinearLayout brandText = new LinearLayout(this);
        brandText.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams btp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btp.leftMargin = d(11);

        TextView title = new TextView(this);
        title.setText("Tile Manager");
        title.setTextColor(ON_SURFACE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        brandText.addView(title);

        lblConnSub = new TextView(this);
        lblConnSub.setText("Checking HMG-API…");
        lblConnSub.setTextColor(ON_SURFACE_VAR);
        lblConnSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        lblConnSub.setSingleLine(true);
        brandText.addView(lblConnSub);

        brand.addView(brandText, btp);
        rail.addView(brand);

        // 2. Hero Visibility & Gap-Compaction Card
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setBackground(shape(SURFACE_HIGH, OUTLINE_VAR, Math.max(1, d(1)), d(18)));
        hero.setPadding(d(14), d(12), d(14), d(11));
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heroLp.topMargin = d(12);

        LinearLayout countRow = new LinearLayout(this);
        countRow.setOrientation(LinearLayout.HORIZONTAL);
        countRow.setGravity(Gravity.CENTER_VERTICAL);

        lblHeroCount = new TextView(this);
        lblHeroCount.setText("—");
        lblHeroCount.setTextColor(ON_SURFACE);
        lblHeroCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f);
        lblHeroCount.setTypeface(Typeface.DEFAULT_BOLD);
        lblHeroCount.setSingleLine(true);
        countRow.addView(lblHeroCount, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        lblHeroChip = TileWidgets.pillBadge(this, "All Shown", OK_CONT, ON_OK_CONT);
        countRow.addView(lblHeroChip);
        hero.addView(countRow);

        TextView heroSub = new TextView(this);
        heroSub.setText("Hidden tiles are backed up and restored automatically.");
        heroSub.setTextColor(ON_SURFACE_VAR);
        heroSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        LinearLayout.LayoutParams hsp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hsp.topMargin = d(4);
        hero.addView(heroSub, hsp);

        View div = new View(this);
        div.setBackgroundColor(OUTLINE_VAR);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, d(1)));
        dp.topMargin = d(10);
        dp.bottomMargin = d(8);
        hero.addView(div, dp);

        // Close Empty Gaps switch row inside hero card
        LinearLayout gapRow = new LinearLayout(this);
        gapRow.setOrientation(LinearLayout.HORIZONTAL);
        gapRow.setGravity(Gravity.CENTER_VERTICAL);

        View gapIcon = new View(this);
        gapIcon.setBackground(new TileIcons(TileIcons.COMPACT, PRIMARY));
        gapRow.addView(gapIcon, new LinearLayout.LayoutParams(d(18), d(18)));

        LinearLayout gapCols = new LinearLayout(this);
        gapCols.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gcp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        gcp.leftMargin = d(9);
        gcp.rightMargin = d(6);

        TextView gapTitle = new TextView(this);
        gapTitle.setText("Close Empty Gaps");
        gapTitle.setTextColor(ON_SURFACE);
        gapTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        gapTitle.setTypeface(Typeface.DEFAULT_BOLD);
        gapTitle.setSingleLine(true);
        gapCols.addView(gapTitle);

        TextView gapSub = new TextView(this);
        gapSub.setText("Shift remaining tiles left");
        gapSub.setTextColor(ON_SURFACE_VAR);
        gapSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        gapSub.setSingleLine(true);
        gapCols.addView(gapSub);
        gapRow.addView(gapCols, gcp);

        swCompactGaps = new TileWidgets.M3Switch(this, compactGaps, new TileWidgets.ToggleListener() {
            @Override
            public void onToggle(boolean checked) {
                compactGaps = checked;
                persistAndBroadcast();
            }
        });
        gapRow.addView(swCompactGaps);
        gapRow.setClickable(true);
        gapRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !compactGaps;
                compactGaps = next;
                swCompactGaps.setChecked(next, true);
                persistAndBroadcast();
            }
        });
        hero.addView(gapRow);
        rail.addView(hero, heroLp);

        // 3. M3 Navigation Destinations
        LinearLayout navBox = new LinearLayout(this);
        navBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        navLp.topMargin = d(12);
        navLp.gravity = Gravity.BOTTOM;

        View spacer = new View(this);
        navBox.addView(spacer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        navBox.addView(buildNavItem(TAB_ALL, TileIcons.GRID_ALL, "All Home Tiles"));
        navBox.addView(buildNavItem(TAB_PACKAGES, TileIcons.PACKAGE, "Packages & Mods"));
        navBox.addView(buildNavItem(TAB_SYSTEM, TileIcons.SYSTEM, "System Apps"));

        rail.addView(navBox, navLp);
        return rail;
    }

    private View buildNavItem(final int idx, int iconKind, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(d(14), 0, d(12), 0);
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab(idx);
            }
        });

        TileIcons ic = new TileIcons(iconKind, ON_SURFACE_VAR);
        View iv = new View(this);
        iv.setBackground(ic);
        row.addView(iv, new LinearLayout.LayoutParams(d(20), d(20)));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        tv.setSingleLine(true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = d(12);
        row.addView(tv, tp);

        TextView badge = new TextView(this);
        badge.setText("0");
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setPadding(d(8), d(2), d(8), d(2));
        row.addView(badge);

        navItems[idx] = row;
        navIcons[idx] = ic;
        navLabels[idx] = tv;
        navBadges[idx] = badge;

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, d(46));
        if (idx > 0) lp.topMargin = d(5);
        row.setLayoutParams(lp);
        return row;
    }

    private View buildRightHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(shape(SURFACE_LOW, OUTLINE_VAR, Math.max(1, d(1)), d(18)));
        header.setPadding(d(18), d(11), d(12), d(11));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.rightMargin = d(12);

        pageTitle = new TextView(this);
        pageTitle.setTextColor(ON_SURFACE);
        pageTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f);
        pageTitle.setTypeface(Typeface.DEFAULT_BOLD);
        pageTitle.setSingleLine(true);
        titles.addView(pageTitle);

        pageSubtitle = new TextView(this);
        pageSubtitle.setTextColor(ON_SURFACE_VAR);
        pageSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        pageSubtitle.setSingleLine(true);
        pageSubtitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = d(2);
        titles.addView(pageSubtitle, sp);
        header.addView(titles, tlp);

        lblSaveStatus = new TextView(this);
        lblSaveStatus.setText("");
        lblSaveStatus.setTextColor(OK);
        lblSaveStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        lblSaveStatus.setTypeface(Typeface.DEFAULT_BOLD);
        lblSaveStatus.setSingleLine(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.rightMargin = d(10);
        header.addView(lblSaveStatus, slp);

        LinearLayout btnRefresh = TileWidgets.m3Button(
                this, TileIcons.REFRESH, "Refresh", TileWidgets.BTN_OUTLINED, 38,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        requestLauncherCatalog();
                        reloadFromDisk(false);
                        showStatus("Refreshing catalog…");
                    }
                });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.rightMargin = d(8);
        header.addView(btnRefresh, rp);

        btnShowAll = TileWidgets.m3Button(
                this, TileIcons.SHOW_ALL, "Show All", TileWidgets.BTN_TONAL, 38,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (hiddenKeys.isEmpty()) {
                            Toast.makeText(MainActivity.this, "All tiles are already visible.", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        hiddenKeys.clear();
                        renderAll();
                        persistAndBroadcast();
                    }
                });
        LinearLayout.LayoutParams sap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sap.rightMargin = d(8);
        header.addView(btnShowAll, sap);

        FrameLayout btnClose = TileWidgets.circleIconButton(
                this, TileIcons.CLOSE, SURFACE_HIGH, ON_SURFACE, OUTLINE_VAR, 38,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        finish();
                    }
                });
        header.addView(btnClose);
        return header;
    }

    private void selectTab(int tab) {
        activeTab = tab;
        for (int i = 0; i < 3; i++) {
            boolean sel = (i == tab);
            navItems[i].setBackground(interactiveShape(
                    sel ? SECONDARY_CONT : Color.TRANSPARENT, 0, 0, d(23), STATE_ON_SURFACE));
            navIcons[i].setColor(sel ? ON_SECONDARY_CONT : ON_SURFACE_VAR);
            navLabels[i].setTextColor(sel ? ON_SECONDARY_CONT : ON_SURFACE_VAR);
            navLabels[i].setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            navBadges[i].setBackground(shape(
                    sel ? PRIMARY_CONT : SURFACE_HIGH, 0, 0, d(10)));
            navBadges[i].setTextColor(sel ? ON_PRIMARY_CONT : ON_SURFACE_VAR);
        }
        if (tab == TAB_PACKAGES) {
            pageTitle.setText("Packages & Mods");
            pageSubtitle.setText("HMG-API mods, NEMESIS shortcuts, and user-installed apps on the Gen5W home screen.");
        } else if (tab == TAB_SYSTEM) {
            pageTitle.setText("System Apps");
            pageSubtitle.setText("Built-in head-unit apps — toggle OFF to hide from both Home Screen and Menu Editor.");
        } else {
            pageTitle.setText("All Home Tiles");
            pageSubtitle.setText("Toggle any tile OFF to hide it live; toggle ON to restore it to its saved slot.");
        }
        renderGrid();
    }

    // ---- Data Loading & Live Persistence ----

    private void reloadFromDisk(final boolean askLauncherIfEmpty) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                final boolean connected;
                JSONObject daemonSettings = null;
                HmgApi api = HmgApi.connect().timeout(1500);
                connected = api.available();
                if (connected) {
                    try {
                        daemonSettings = api.settings();
                    } catch (Throwable ignored) {}
                }

                final String cfgJson = TileCatalogProvider.readEffectiveConfigJson(MainActivity.this);
                final String catJson = TileCatalogProvider.readCatalogJson(MainActivity.this);
                final JSONObject finalDaemon = daemonSettings;

                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        hmgApiConnected = connected;
                        applyLoadedState(cfgJson, finalDaemon, catJson);
                        if (askLauncherIfEmpty) {
                            requestLauncherCatalog();
                        }
                    }
                });
            }
        });
    }

    private void applyLoadedState(String cfgJson, JSONObject daemonSettings, String catJson) {
        Set<String> loadedHidden = new LinkedHashSet<String>();
        boolean loadedCompact = true;
        boolean parsedLocal = false;

        if (cfgJson != null && !cfgJson.trim().isEmpty()) {
            try {
                JSONObject o = new JSONObject(cfgJson);
                loadedCompact = o.optBoolean("compact_gaps", true);
                JSONArray arr = o.optJSONArray("hidden");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        String k = TileCatalogProvider.normKeyString(arr.optString(i, ""));
                        if (!k.isEmpty()) loadedHidden.add(k);
                    }
                }
                parsedLocal = true;
            } catch (Throwable ignored) {}
        }

        if (!parsedLocal && daemonSettings != null) {
            loadedCompact = daemonSettings.optBoolean("compact_gaps", true);
            String raw = daemonSettings.optString("hidden_keys", "");
            if (raw != null && !raw.isEmpty()) {
                for (String part : raw.split(",")) {
                    String k = TileCatalogProvider.normKeyString(part);
                    if (!k.isEmpty()) loadedHidden.add(k);
                }
            }
        }

        List<TileWidgets.TileItem> items = new ArrayList<TileWidgets.TileItem>();
        Set<String> seenKeys = new LinkedHashSet<String>();
        if (catJson != null && !catJson.trim().isEmpty()) {
            try {
                JSONObject doc = new JSONObject(catJson);
                JSONArray arr = doc.optJSONArray("tiles");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject row = arr.optJSONObject(i);
                        if (row == null) continue;
                        String pkg = row.optString("packageName", "").trim();
                        String cls = row.optString("className", "").trim();
                        String key = TileCatalogProvider.normKey(pkg, cls);
                        if (key.isEmpty() || seenKeys.contains(key)) continue;
                        seenKeys.add(key);
                        String title = row.optString("title", "").trim();
                        if (title.isEmpty()) title = fallbackTitle(pkg, cls);
                        String kind = "package".equals(row.optString("kind", "")) ? "package" : "system";
                        String mod = row.has("mod") ? row.optString("mod", null) : null;
                        items.add(new TileWidgets.TileItem(key, pkg, cls, title, kind, mod, false));
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Also surface any hidden keys not yet in catalog so the user can always unhide them
        for (String hk : loadedHidden) {
            if (seenKeys.contains(hk)) continue;
            int slash = hk.indexOf('/');
            if (slash <= 0) continue;
            String pkg = hk.substring(0, slash);
            String cls = hk.substring(slash + 1);
            String kind = (pkg.startsWith("com.hkmc.") || pkg.startsWith("com.Hyundai.")
                    || pkg.startsWith("com.android.")) ? "system" : "package";
            items.add(new TileWidgets.TileItem(hk, pkg, cls, fallbackTitle(pkg, cls), kind, null, true));
        }

        Collections.sort(items, new Comparator<TileWidgets.TileItem>() {
            @Override
            public int compare(TileWidgets.TileItem a, TileWidgets.TileItem b) {
                if (a.isPackage() != b.isPackage()) {
                    return a.isPackage() ? -1 : 1;
                }
                int c = a.title.compareToIgnoreCase(b.title);
                return c != 0 ? c : a.key.compareTo(b.key);
            }
        });

        compactGaps = loadedCompact;
        hiddenKeys.clear();
        hiddenKeys.addAll(loadedHidden);
        catalogItems.clear();
        catalogItems.addAll(items);

        swCompactGaps.setChecked(compactGaps, false);
        renderAll();
    }

    private void requestLauncherCatalog() {
        try {
            Intent req = new Intent(TileCatalogProvider.ACTION_REQUEST_CATALOG);
            sendBroadcast(req);
        } catch (Throwable ignored) {}
    }

    private void persistAndBroadcast() {
        final boolean snapCompact = compactGaps;
        final Set<String> snapHidden = new LinkedHashSet<String>(hiddenKeys);
        final int gen = ++saveGeneration;

        lblSaveStatus.setText("Applying live…");
        lblSaveStatus.setTextColor(PRIMARY);

        io.execute(new Runnable() {
            @Override
            public void run() {
                String configPayload = buildBroadcastConfigJson(snapCompact, snapHidden);
                // 1. Write immediately to local provider store so launcher3/homesetting pull fresh config
                TileCatalogProvider.writeLocalConfig(MainActivity.this, snapCompact, snapHidden, false);

                // 2. Broadcast live update to com.android.launcher3 and com.hkmc.system.app.homesetting
                try {
                    Intent changed = new Intent(TileCatalogProvider.ACTION_TILES_CHANGED);
                    changed.putExtra("config_json", configPayload);
                    sendBroadcast(changed);
                } catch (Throwable ignored) {}

                // 3. Persist to HMG-API mod settings (/data/hmgapi/settings/hmgapi-tile-manager.json)
                boolean syncedDaemon = false;
                try {
                    HmgApi api = HmgApi.connect().timeout(3000);
                    if (api.available()) {
                        JSONObject s = new JSONObject();
                        s.put("configured", true);
                        s.put("compact_gaps", snapCompact);
                        s.put("hidden_keys", joinKeys(snapHidden));
                        api.saveSettings(s);
                        syncedDaemon = true;
                        TileCatalogProvider.writeLocalConfig(MainActivity.this, snapCompact, snapHidden, true);
                    }
                } catch (Throwable ignored) {}

                final boolean okDaemon = syncedDaemon;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || gen != saveGeneration) return;
                        hmgApiConnected = okDaemon || hmgApiConnected;
                        updateRailHeader();
                        showStatus("Home screen updated");
                    }
                });
            }
        });
    }

    private void showStatus(String msg) {
        lblSaveStatus.setText(msg);
        lblSaveStatus.setTextColor(OK);
        final int gen = saveGeneration;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && gen == saveGeneration) {
                    lblSaveStatus.setText("");
                }
            }
        }, 2800);
    }

    // ---- Rendering ----

    private void renderAll() {
        updateRailHeader();
        updateCountsAndBadges();
        renderGrid();
    }

    private void updateRailHeader() {
        if (hmgApiConnected) {
            lblConnSub.setText("● HMG-API Live");
            lblConnSub.setTextColor(OK);
        } else {
            lblConnSub.setText("● Local Provider Mode");
            lblConnSub.setTextColor(ON_SURFACE_VAR);
        }
    }

    private void updateCountsAndBadges() {
        int totalAll = catalogItems.size();
        int totalPkg = 0, totalSys = 0;
        int hiddenInCatalog = 0;
        for (TileWidgets.TileItem it : catalogItems) {
            if (it.isPackage()) totalPkg++;
            else totalSys++;
            if (hiddenKeys.contains(it.key)) hiddenInCatalog++;
        }
        int totalHidden = Math.max(hiddenKeys.size(), hiddenInCatalog);
        if (totalAll > 0) {
            int vis = Math.max(0, totalAll - hiddenInCatalog);
            lblHeroCount.setText(vis + " / " + totalAll + " Visible");
        } else {
            lblHeroCount.setText(totalHidden == 0 ? "All Visible" : (totalHidden + " Hidden"));
        }

        if (totalHidden == 0) {
            lblHeroChip.setText("All Shown");
            lblHeroChip.setBackground(shape(OK_CONT, 0, 0, d(8)));
            lblHeroChip.setTextColor(ON_OK_CONT);
            btnShowAll.setAlpha(0.55f);
        } else {
            lblHeroChip.setText(totalHidden + " Hidden");
            lblHeroChip.setBackground(shape(WARN_CONT, 0, 0, d(8)));
            lblHeroChip.setTextColor(ON_WARN_CONT);
            btnShowAll.setAlpha(1f);
        }

        navBadges[TAB_ALL].setText(String.valueOf(totalAll));
        navBadges[TAB_PACKAGES].setText(String.valueOf(totalPkg));
        navBadges[TAB_SYSTEM].setText(String.valueOf(totalSys));
    }

    private void renderGrid() {
        gridContainer.removeAllViews();

        List<TileWidgets.TileItem> filtered = new ArrayList<TileWidgets.TileItem>();
        Map<String, Integer> pkgCounts = new HashMap<String, Integer>();
        for (TileWidgets.TileItem it : catalogItems) {
            Integer prev = pkgCounts.get(it.packageName);
            pkgCounts.put(it.packageName, Integer.valueOf(prev == null ? 1 : prev.intValue() + 1));
            if (activeTab == TAB_PACKAGES && !it.isPackage()) continue;
            if (activeTab == TAB_SYSTEM && it.isPackage()) continue;
            filtered.add(it);
        }

        if (filtered.isEmpty()) {
            gridContainer.addView(buildEmptyStateCard());
            return;
        }

        // 2-column Gen5W automotive card grid
        for (int i = 0; i < filtered.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) rowLp.topMargin = d(9);

            TileWidgets.TileItem leftItem = filtered.get(i);
            boolean leftMulti = pkgCounts.get(leftItem.packageName).intValue() > 1;
            View leftCard = createCard(leftItem, leftMulti);
            LinearLayout.LayoutParams lpLeft = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lpLeft.rightMargin = d(5);
            row.addView(leftCard, lpLeft);

            if (i + 1 < filtered.size()) {
                TileWidgets.TileItem rightItem = filtered.get(i + 1);
                boolean rightMulti = pkgCounts.get(rightItem.packageName).intValue() > 1;
                View rightCard = createCard(rightItem, rightMulti);
                LinearLayout.LayoutParams lpRight = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lpRight.leftMargin = d(5);
                row.addView(rightCard, lpRight);
            } else {
                View blank = new View(this);
                LinearLayout.LayoutParams lpBlank = new LinearLayout.LayoutParams(0, 1, 1f);
                lpBlank.leftMargin = d(5);
                row.addView(blank, lpBlank);
            }
            gridContainer.addView(row, rowLp);
        }
    }

    private View createCard(final TileWidgets.TileItem item, boolean showSubClass) {
        boolean vis = !hiddenKeys.contains(item.key);
        final TileWidgets.TileCardView[] holder = new TileWidgets.TileCardView[1];
        TileWidgets.TileCardView card = new TileWidgets.TileCardView(
                this, item, vis, showSubClass, new TileWidgets.ToggleListener() {
                    @Override
                    public void onToggle(boolean checked) {
                        onTileVisibilityRequested(item, checked, holder[0]);
                    }
                });
        holder[0] = card;
        return card;
    }

    private void onTileVisibilityRequested(final TileWidgets.TileItem item, boolean visible,
                                           final TileWidgets.TileCardView card) {
        if (visible) {
            hiddenKeys.remove(item.key);
            updateCountsAndBadges();
            persistAndBroadcast();
            return;
        }

        String warning = safetyWarningFor(item);
        if (warning != null) {
            new AlertDialog.Builder(this)
                    .setTitle("Hide \"" + item.title + "\"?")
                    .setMessage(warning)
                    .setNegativeButton("Keep Visible", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            card.setTileVisible(true, true);
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialog) {
                            card.setTileVisible(true, true);
                        }
                    })
                    .setPositiveButton("Hide Tile", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            hiddenKeys.add(item.key);
                            card.setTileVisible(false, false);
                            updateCountsAndBadges();
                            persistAndBroadcast();
                        }
                    })
                    .show();
            return;
        }

        hiddenKeys.add(item.key);
        updateCountsAndBadges();
        persistAndBroadcast();
    }

    private static String safetyWarningFor(TileWidgets.TileItem item) {
        String pkg = item.packageName;
        if ("dev.lemoyne.tiles".equals(pkg)) {
            return "Hiding Tile Manager removes its shortcut from the Home Screen.\n\n"
                    + "You can still open it anytime from NEMESIS Manager → Launch, or by assigning "
                    + "\"Home Screen Tile Manager\" to the steering-wheel / dash ★ Custom button in HMG-API.";
        }
        if ("hmgapi.app".equals(pkg)) {
            return "Hiding HMG-API removes its tile from the Home Screen.\n\n"
                    + "Make sure you keep Tile Manager or NEMESIS Manager visible so you can re-enable it later.";
        }
        if ("com.nemesis.manager".equals(pkg) || pkg.startsWith("com.nemesis.")) {
            return "Hiding NEMESIS removes its Home Screen shortcut.\n\n"
                    + "You can restore it anytime from Tile Manager → Packages & Mods.";
        }
        String lowTitle = item.title.toLowerCase(Locale.US);
        if ("com.android.settings".equals(pkg) || "setup".equals(lowTitle) || "settings".equals(lowTitle)) {
            return "Hiding Setup removes the vehicle settings tile from the Home Screen (physical Setup button still works).";
        }
        return null;
    }

    private View buildEmptyStateCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(shape(SURFACE_CONT, OUTLINE_VAR, Math.max(1, d(1)), d(20)));
        card.setPadding(d(28), d(32), d(28), d(32));

        card.addView(TileWidgets.iconBadge(this, TileIcons.SHIELD, PRIMARY_CONT, ON_PRIMARY_CONT, 52, 26));

        TextView t = new TextView(this);
        t.setText(catalogItems.isEmpty()
                ? "Waiting for Home Screen Tile Catalog"
                : "No Tiles in This Category");
        t.setTextColor(ON_SURFACE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = d(14);
        card.addView(t, tp);

        TextView sub = new TextView(this);
        sub.setText(catalogItems.isEmpty()
                ? "Visit the Gen5W Home Screen once after installing or applying HMG-API so the launcher hook "
                  + "can index your vehicle's stock apps and installed mod tiles, then tap Scan Home Screen below."
                : "All discovered tiles are currently listed under the other category tab.");
        sub.setTextColor(ON_SURFACE_VAR);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                d(620), ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = d(8);
        card.addView(sub, sp);

        if (catalogItems.isEmpty()) {
            LinearLayout btnScan = TileWidgets.m3Button(
                    this, TileIcons.REFRESH, "Scan Home Screen Now", TileWidgets.BTN_FILLED, 42,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            requestLauncherCatalog();
                            reloadFromDisk(false);
                            showStatus("Requested catalog from launcher…");
                        }
                    });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bp.topMargin = d(18);
            card.addView(btnScan, bp);
        }
        return card;
    }

    // ---- Helpers ----

    private static String fallbackTitle(String pkg, String cls) {
        if (cls != null && !cls.isEmpty()) {
            int dot = cls.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < cls.length()) return cls.substring(dot + 1);
            return cls;
        }
        return pkg;
    }

    private static String joinKeys(Set<String> keys) {
        StringBuilder b = new StringBuilder();
        int i = 0;
        for (String k : keys) {
            if (i++ > 0) b.append(',');
            b.append(k);
        }
        return b.toString();
    }

    private static String buildBroadcastConfigJson(boolean compact, Set<String> hidden) {
        try {
            JSONObject o = new JSONObject();
            o.put("configured", true);
            o.put("compact_gaps", compact);
            JSONArray a = new JSONArray();
            for (String k : hidden) a.put(k);
            o.put("hidden", a);
            return o.toString();
        } catch (Throwable ignored) {
            return "{}";
        }
    }

    private int d(float dpVal) {
        return dp(this, dpVal);
    }
}
