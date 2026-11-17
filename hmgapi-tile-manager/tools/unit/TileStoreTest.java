package dev.lemoyne.tiles.hook;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure-JVM unit tests for TileStore:
 *  - key normalisation (.Activity shorthand vs full className)
 *  - JSON parser/writer escaping & config parsing (array "hidden" + CSV "hidden_keys")
 *  - hiding & restoring both stock (itemId 1..38) and mod (itemId >= 9000) tiles at exact slots
 *  - pruning uninstalled mod/app backups on restore so phantom tiles never reappear
 *  - gap compaction (compact_gaps=true vs compact_gaps=false)
 *  - multi-profile (Driver 1 / Driver 2) backup isolation & idempotence
 */
public final class TileStoreTest {
    private static int checks = 0;

    private static void check(String label, Object expected, Object actual) {
        checks++;
        boolean eq = (expected == null) ? (actual == null) : expected.equals(actual);
        if (!eq) {
            throw new AssertionError("FAIL [" + label + "]: expected <" + expected + ">, got <" + actual + ">");
        }
    }

    private static void checkTrue(String label, boolean cond) {
        check(label, Boolean.TRUE, Boolean.valueOf(cond));
    }

    private static Set<String> setOf(String... items) {
        return new LinkedHashSet<String>(Arrays.asList(items));
    }

    @SuppressWarnings("unchecked")
    private static String slotPkg(String menuJson, int slot) {
        Map<String, Object> m = TileStore.parseObject(menuJson);
        List<Object> arr = (List<Object>) m.get("menuItems");
        Map<String, Object> it = (Map<String, Object>) arr.get(slot);
        return TileStore.str(it.get("packageName"));
    }

    @SuppressWarnings("unchecked")
    private static int slotItemId(String menuJson, int slot) {
        Map<String, Object> m = TileStore.parseObject(menuJson);
        List<Object> arr = (List<Object>) m.get("menuItems");
        Map<String, Object> it = (Map<String, Object>) arr.get(slot);
        return TileStore.intVal(it.get("itemId"), -999);
    }

    private static String sampleGrid() {
        return "{\"menuItems\":["
                + "{\"packageName\":\"com.hkmc.ev\",\"itemId\":3,\"className\":\"com.hkmc.ev.EvActivity\"},"
                + "{\"packageName\":\"com.hkmc.radio\",\"itemId\":8,\"className\":\".RadioActivity\"},"
                + "{\"packageName\":\"dev.lemoyne.comfort\",\"itemId\":9001,\"className\":\"dev.lemoyne.comfort.MainActivity\"},"
                + "{\"packageName\":\"hmgapi.app\",\"itemId\":9000,\"className\":\"hmgapi.app.MainActivity\"},"
                + "{\"packageName\":\"Empty view\",\"itemId\":39,\"className\":\"Empty view\"}"
                + "]}";
    }

    public static void main(String[] args) throws Exception {
        // 1. Key normalisation
        check("normKey shorthand class", "com.hkmc.radio/com.hkmc.radio.RadioActivity",
                TileStore.normKey("com.hkmc.radio", ".RadioActivity"));
        check("normKey full class", "dev.lemoyne.comfort/dev.lemoyne.comfort.MainActivity",
                TileStore.normKey("dev.lemoyne.comfort", "dev.lemoyne.comfort.MainActivity"));
        check("normKeyString shorthand", "dev.lemoyne.tiles/dev.lemoyne.tiles.MainActivity",
                TileStore.normKeyString(" dev.lemoyne.tiles/.MainActivity "));
        check("normKeyString malformed", "", TileStore.normKeyString("no-slash"));

        // 2. Config JSON & CSV parsing + signature determinism
        TileStore.Config c1 = TileStore.parseConfig(
                "{\"compact_gaps\":false,\"hidden\":[\"b/.Two\",\"a/a.One\"]}", true);
        check("parseConfig exists", Boolean.TRUE, Boolean.valueOf(c1.exists));
        check("parseConfig compactGaps=false", Boolean.FALSE, Boolean.valueOf(c1.compactGaps));
        check("parseConfig expands shorthand", Boolean.TRUE, Boolean.valueOf(c1.hidden.contains("b/b.Two")));

        TileStore.Config c2 = TileStore.parseConfig(
                "{\"configured\":true,\"compact_gaps\":false,\"hidden_keys\":\"a/a.One, b/b.Two\"}", false);
        check("CSV config matches JSON array signature", c1.signature(true), c2.signature(true));
        checkTrue("mod disabled changes signature", !c1.signature(true).equals(c1.signature(false)));

        // 3. Hide stock + mod tile WITH gap compaction
        Map<String, Object> backups = new LinkedHashMap<String, Object>();
        Set<String> installed = setOf(
                "com.hkmc.ev/com.hkmc.ev.EvActivity",
                "com.hkmc.radio/com.hkmc.radio.RadioActivity",
                "dev.lemoyne.comfort/dev.lemoyne.comfort.MainActivity",
                "hmgapi.app/hmgapi.app.MainActivity");

        TileStore.Config hideTwo = new TileStore.Config(true, true, setOf(
                "com.hkmc.radio/com.hkmc.radio.RadioActivity",
                "dev.lemoyne.comfort/dev.lemoyne.comfort.MainActivity"));
        TileStore.SyncResult r1 = TileStore.syncProfileMenu("Driver 1", sampleGrid(), hideTwo, installed, backups);
        checkTrue("hideTwo updates menu", r1.updatedMenuJson != null);
        checkTrue("hideTwo updates backup", r1.backupChanged);
        // Slots 0 and 1 should now hold EV and HMG-API (compacted left!), slots 2..4 empty
        check("compacted slot 0 is EV", "com.hkmc.ev", slotPkg(r1.updatedMenuJson, 0));
        check("compacted slot 1 is HMG-API", "hmgapi.app", slotPkg(r1.updatedMenuJson, 1));
        check("compacted slot 2 is Empty", "Empty view", slotPkg(r1.updatedMenuJson, 2));
        check("compacted slot 3 is Empty", "Empty view", slotPkg(r1.updatedMenuJson, 3));
        checkTrue("all_menu JSON keeps integer itemId without .0", !r1.updatedMenuJson.contains(".0"));

        // 4. Idempotence when nothing changes on second pass
        TileStore.SyncResult rIdem = TileStore.syncProfileMenu("Driver 1", r1.updatedMenuJson, hideTwo, installed, backups);
        check("idempotent second pass returns null menu diff", null, rIdem.updatedMenuJson);
        check("idempotent second pass leaves backup untouched", Boolean.FALSE, Boolean.valueOf(rIdem.backupChanged));

        // 5. Restore when one tile was uninstalled while hidden (prunes uninstalled, restores installed with exact itemId)
        Set<String> afterUninstall = setOf(
                "com.hkmc.ev/com.hkmc.ev.EvActivity",
                "com.hkmc.radio/com.hkmc.radio.RadioActivity",
                "hmgapi.app/hmgapi.app.MainActivity"); // comfort was uninstalled!
        TileStore.Config showAll = new TileStore.Config(true, true, Collections.<String>emptySet());
        TileStore.SyncResult rRestore = TileStore.syncProfileMenu(
                "Driver 1", r1.updatedMenuJson, showAll, afterUninstall, backups);
        checkTrue("restore updates menu", rRestore.updatedMenuJson != null);
        check("Radio restored into first available slot (slot 2)", "com.hkmc.radio", slotPkg(rRestore.updatedMenuJson, 2));
        check("Radio preserves original stock itemId 8", Integer.valueOf(8), Integer.valueOf(slotItemId(rRestore.updatedMenuJson, 2)));
        check("Uninstalled comfort mod NOT restored as phantom tile", "Empty view", slotPkg(rRestore.updatedMenuJson, 3));
        Map<?, ?> profilesAfter = (Map<?, ?>) backups.get("profiles");
        check("Empty profile backup cleaned up", Boolean.TRUE, Boolean.valueOf(profilesAfter.isEmpty()));

        // 6. Hide WITHOUT gap compaction preserves exact original slot on unhide
        TileStore.Config hideNoCompact = new TileStore.Config(true, false, setOf(
                "com.hkmc.radio/com.hkmc.radio.RadioActivity"));
        TileStore.SyncResult rNoComp = TileStore.syncProfileMenu("Driver 2", sampleGrid(), hideNoCompact, installed, backups);
        check("slot 1 becomes Empty view in place", "Empty view", slotPkg(rNoComp.updatedMenuJson, 1));
        check("slot 2 stays Comfort without shifting", "dev.lemoyne.comfort", slotPkg(rNoComp.updatedMenuJson, 2));

        TileStore.Config unhideNoCompact = new TileStore.Config(true, false, Collections.<String>emptySet());
        TileStore.SyncResult rRestExact = TileStore.syncProfileMenu(
                "Driver 2", rNoComp.updatedMenuJson, unhideNoCompact, installed, backups);
        check("Radio returns to exact original slot 1", "com.hkmc.radio", slotPkg(rRestExact.updatedMenuJson, 1));
        check("Radio keeps itemId 8", Integer.valueOf(8), Integer.valueOf(slotItemId(rRestExact.updatedMenuJson, 1)));

        // 7. Atomic file round-trip & string escaping
        File tmp = File.createTempFile("tilestore-test", ".json");
        tmp.deleteOnExit();
        Map<String, Object> special = new LinkedHashMap<String, Object>();
        special.put("quote", "Kia \"EV6\"\nLine2\tTab\\Back");
        special.put("num", Long.valueOf(9001));
        TileStore.writeAtomic(tmp, TileStore.writeCompact(special));
        Map<String, Object> parsed = TileStore.parseObject(TileStore.readUtf8(tmp));
        check("JSON escape round-trip", "Kia \"EV6\"\nLine2\tTab\\Back", parsed.get("quote"));
        check("JSON number round-trip", Long.valueOf(9001), parsed.get("num"));
        tmp.delete();

        // 8. Package-only (pkg/*), virtual class (@class/*), and itemId (@item/*) keys & wildcard matching
        check("normKey package-only", "com.mnsoft.navi/*", TileStore.normKey("com.mnsoft.navi", "", 2));
        check("normKey virtual class", "@class/Radio", TileStore.normKey("", "Radio", 8));
        check("normKey itemId fallback", "@item/14", TileStore.normKey("", "", 14));
        check("normKeyString package wildcard", "com.mnsoft.navi/*", TileStore.normKeyString("com.mnsoft.navi/*"));
        check("normKeyString virtual class", "@class/Radio", TileStore.normKeyString("@class/Radio"));
        check("normKeyString itemId", "@item/14", TileStore.normKeyString("@item/14"));

        Set<String> wildcardHidden = setOf("com.mnsoft.navi/*", "com.hkmc.ev/com.hkmc.ev.EvActivity", "@class/Radio");
        check("wildcard hidden matches full class", "com.mnsoft.navi/*",
                TileStore.matchingHiddenKey(wildcardHidden, "com.mnsoft.navi", "com.mnsoft.navi.Main", 2));
        check("full class hidden matches empty-cls all_menu entry", "com.hkmc.ev/com.hkmc.ev.EvActivity",
                TileStore.matchingHiddenKey(wildcardHidden, "com.hkmc.ev", "", 3));
        check("virtual class hidden matches", "@class/Radio",
                TileStore.matchingHiddenKey(wildcardHidden, "", "Radio", 8));

        // 9. Trim/region ghost stock filtering (Built-in Cam, Memory Seat Configuration, Sound Mood Lamp, VIBE)
        String i5Menu = "{\"menuItems\":["
                + "{\"packageName\":\"com.hkmc.ev\",\"itemId\":1,\"className\":\".EvActivity\"},"
                + "{\"packageName\":\"com.mnsoft.navi\",\"itemId\":2,\"className\":\"\"},"
                + "{\"packageName\":\"com.hkmc.carpay\",\"itemId\":5,\"className\":\".CarPayActivity\"},"
                + "{\"packageName\":\"\",\"itemId\":8,\"className\":\"Radio\"},"
                + "{\"packageName\":\"com.hkmc.telematics.bluelink\",\"itemId\":11,\"className\":\".BluelinkMain\"},"
                + "{\"packageName\":\"com.hkmc.notification\",\"itemId\":12,\"className\":\".NoticeListActivity\"},"
                + "{\"packageName\":\"com.hkmc.qrmanual\",\"itemId\":15,\"className\":\"\"},"
                + "{\"packageName\":\"com.android.settings\",\"itemId\":20,\"className\":\"com.android.settings.Settings\"},"
                + "{\"packageName\":\"Empty view\",\"itemId\":39,\"className\":\"Empty view\"}"
                + "]}";
        TileStore.MenuInventory i5Inv = TileStore.collectMenuInventory(
                Collections.singletonList(i5Menu), Collections.<String, Object>emptyMap());
        Set<String> noHidden = Collections.emptySet();

        // Equipped stock tiles on this US Ioniq 5 MUST pass
        checkTrue("EV equipped", TileStore.isEquippedSystemTile(
                "com.hkmc.ev/com.hkmc.ev.EvActivity", "com.hkmc.ev", ".EvActivity", i5Inv, noHidden));
        checkTrue("Navigation Menu (pkg-only) equipped", TileStore.isEquippedSystemTile(
                "com.mnsoft.navi/com.mnsoft.navi.NaviMenuActivity", "com.mnsoft.navi", ".NaviMenuActivity", i5Inv, noHidden));
        checkTrue("Hyundai Pay equipped", TileStore.isEquippedSystemTile(
                "com.hkmc.carpay/com.hkmc.carpay.CarPayActivity", "com.hkmc.carpay", ".CarPayActivity", i5Inv, noHidden));
        checkTrue("Radio (@class) equipped", TileStore.isEquippedSystemTile(
                "@class/Radio", "", "Radio", i5Inv, noHidden));
        checkTrue("Setup (com.android.settings.Settings) equipped", TileStore.isEquippedSystemTile(
                "com.android.settings/com.android.settings.Settings", "com.android.settings", "com.android.settings.Settings", i5Inv, noHidden));

        // Unequipped trim/region ghost entries MUST be excluded (including Memory Seat sharing com.android.settings!)
        check("Built-in Cam excluded", Boolean.FALSE, Boolean.valueOf(TileStore.isEquippedSystemTile(
                "com.hkmc.dvrs/com.hkmc.dvrs.MainActivity", "com.hkmc.dvrs", ".MainActivity", i5Inv, noHidden)));
        check("Memory Seat Configuration excluded despite sharing com.android.settings", Boolean.FALSE, Boolean.valueOf(
                TileStore.isEquippedSystemTile("com.android.settings/com.android.settings.ImsSeatActivity",
                        "com.android.settings", "com.android.settings.ImsSeatActivity", i5Inv, noHidden)));
        check("Sound Mood Lamp excluded", Boolean.FALSE, Boolean.valueOf(TileStore.isEquippedSystemTile(
                "com.hkmc.moodlamp/com.hkmc.moodlamp.MoodLampActivity", "com.hkmc.moodlamp", ".MoodLampActivity", i5Inv, noHidden)));
        check("VIBE excluded", Boolean.FALSE, Boolean.valueOf(TileStore.isEquippedSystemTile(
                "com.naver.vibe/com.naver.vibe.MainActivity", "com.naver.vibe", ".MainActivity", i5Inv, noHidden)));

        // 10. EntryInspector field calibration + extraction of 6-arg, 8-arg, custom-title, and itemId entries
        TileStore.EntryInspector inspector = new TileStore.EntryInspector();
        MockQ sample1 = MockQ.ctor7("com.hkmc.phone", ".PhoneActivity", 0x7f020001, 0x7f020002, 0x7f080010, "Phone");
        MockQ sample2 = MockQ.ctor7("com.android.settings", "com.android.settings.Settings", 0x7f020003, 0x7f020004, 0x7f080011, "Setup");
        inspector.calibrate(sample1, "com.hkmc.phone", ".PhoneActivity", 0x7f020001, 0x7f020002, 0x7f080010, "Phone");
        inspector.calibrate(sample2, "com.android.settings", "com.android.settings.Settings", 0x7f020003, 0x7f020004, 0x7f080011, "Setup");

        TileStore.IntStringProbe mockRes = new TileStore.IntStringProbe() {
            @Override
            public String resolveStringResource(int resId) {
                if (resId == 0x7f080021) return "Notifications";
                if (resId == 0x7f080022) return "Navigation Menu";
                if (resId == 0x7f080023) return "Bluelink";
                return null;
            }
        };

        // 8-arg badge constructor (Notifications)
        MockQ notifEntry = MockQ.ctor8Badge("com.hkmc.notification", ".NoticeListActivity", 0x7f020010, 0x7f020011, 0x7f080021, 3);
        TileStore.EntryInspector.Extracted exNotif = inspector.extract(notifEntry, i5Inv, noHidden, mockRes);
        check("8-arg Notifications pkg", "com.hkmc.notification", exNotif.pkg);
        check("8-arg Notifications cls", ".NoticeListActivity", exNotif.cls);
        check("8-arg Notifications titleId", Integer.valueOf(0x7f080021), Integer.valueOf(exNotif.titleId));

        // 6-arg package-only constructor (Navigation Menu)
        MockQ naviEntry = MockQ.ctor6PkgOnly("com.mnsoft.navi", 0x7f020012, 0x7f020013, 0x7f080022);
        TileStore.EntryInspector.Extracted exNavi = inspector.extract(naviEntry, i5Inv, noHidden, mockRes);
        check("6-arg Navigation Menu pkg", "com.mnsoft.navi", exNavi.pkg);
        check("6-arg Navigation Menu key", "com.mnsoft.navi/*", TileStore.normKey(exNavi.pkg, exNavi.cls));

        // Custom/NEMESIS direct-title constructor (titleId = 0)
        MockQ nemesisEntry = MockQ.ctorCustomLabel("com.nemesis.manager", "com.nemesis.manager.MainActivity", "NEMESIS Manager");
        TileStore.EntryInspector.Extracted exNem = inspector.extract(nemesisEntry, i5Inv, noHidden, mockRes);
        check("Custom label pkg", "com.nemesis.manager", exNem.pkg);
        check("Custom label titleText", "NEMESIS Manager", exNem.titleText);
        check("NEMESIS package classified non-stock", Boolean.FALSE, Boolean.valueOf(TileStore.isStockOemPackage(exNem.pkg)));

        // Friendly fallback titles for technical APK labels
        check("QRManual -> User's Manual", "User's Manual",
                TileStore.friendlySystemTitle("QRManual", "com.hkmc.qrmanual", ""));
        check("HKMC_CarPay_WP -> Hyundai Pay", "Hyundai Pay",
                TileStore.friendlySystemTitle("HKMC_CarPay_WP", "com.hkmc.carpay", ".CarPayActivity"));

        // 11. LIFO hook simulation: FilterableMenuList captures & filters mod tiles added AFTER TileModEntry
        final Set<String> lifoHidden = setOf("dev.dibble.lightvibes/dev.dibble.lightvibes.MainActivity");
        List<Object> stockInitial = Arrays.<Object>asList("com.hkmc.ev/com.hkmc.ev.EvActivity", "com.hkmc.phone/com.hkmc.phone.PhoneActivity");
        final List<Object> observedPostAdds = new java.util.ArrayList<Object>();
        TileStore.FilterableMenuList lifoList = new TileStore.FilterableMenuList(stockInitial, new TileStore.ItemVisibilityFilter() {
            @Override
            public void onItemAdded(Object item) {
                observedPostAdds.add(item);
            }
            @Override
            public boolean shouldShow(Object item) {
                return !lifoHidden.contains(String.valueOf(item));
            }
        });
        observedPostAdds.clear();
        // Now HmgApi.launcher (hooks[0] in LIFO) appends custom mod tiles AFTER TileModEntry returned!
        lifoList.add("hmgapi.app/hmgapi.app.MainActivity");
        lifoList.add("dev.lemoyne.comfort/dev.lemoyne.comfort.MainActivity");
        lifoList.add("dev.dibble.lightvibes/dev.dibble.lightvibes.MainActivity");
        lifoList.add("dev.lemoyne.tiles/dev.lemoyne.tiles.MainActivity");

        check("FilterableMenuList rawItems captures all 6 stock + mod tiles", Integer.valueOf(6), Integer.valueOf(lifoList.snapshotRaw().size()));
        check("FilterableMenuList visible size omits hidden Light Vibes (5 visible)", Integer.valueOf(5), Integer.valueOf(lifoList.size()));
        check("FilterableMenuList notified on all 4 post-hook mod additions", Integer.valueOf(4), Integer.valueOf(observedPostAdds.size()));

        // 12. Boot slot preservation when HmgApi.syncSavedMenu temporarily re-inserts a hidden mod tile into an earlier empty slot
        Map<String, Object> bootBackups = new LinkedHashMap<String, Object>();
        String initialBootGrid = "{\"menuItems\":["
                + "{\"packageName\":\"com.hkmc.ev\",\"itemId\":3,\"className\":\"com.hkmc.ev.EvActivity\"},"
                + "{\"packageName\":\"com.hkmc.radio\",\"itemId\":8,\"className\":\"com.hkmc.radio.RadioActivity\"},"
                + "{\"packageName\":\"dev.lemoyne.comfort\",\"itemId\":9001,\"className\":\"dev.lemoyne.comfort.MainActivity\"},"
                + "{\"packageName\":\"dev.dibble.lightvibes\",\"itemId\":9002,\"className\":\"dev.dibble.lightvibes.MainActivity\"}"
                + "]}";
        TileStore.Config hideRadioAndVibesNoCompact = new TileStore.Config(true, false, setOf(
                "com.hkmc.radio/com.hkmc.radio.RadioActivity",
                "dev.dibble.lightvibes/dev.dibble.lightvibes.MainActivity"));
        TileStore.SyncResult preSync = TileStore.syncProfileMenu("Driver 1", initialBootGrid, hideRadioAndVibesNoCompact, null, bootBackups);
        // Simulate HmgApi.syncSavedMenu re-inserting missing Light Vibes into the FIRST empty slot (slot 1, where Radio was!)
        String afterHmgApiBootSync = "{\"menuItems\":["
                + "{\"packageName\":\"com.hkmc.ev\",\"itemId\":3,\"className\":\"com.hkmc.ev.EvActivity\"},"
                + "{\"packageName\":\"dev.dibble.lightvibes\",\"itemId\":9002,\"className\":\"dev.dibble.lightvibes.MainActivity\"},"
                + "{\"packageName\":\"dev.lemoyne.comfort\",\"itemId\":9001,\"className\":\"dev.lemoyne.comfort.MainActivity\"},"
                + "{\"packageName\":\"Empty view\",\"itemId\":39,\"className\":\"Empty view\"}"
                + "]}";
        checkTrue("preSync produced menu JSON", preSync.updatedMenuJson != null);
        TileStore.SyncResult postSync = TileStore.syncProfileMenu("Driver 1", afterHmgApiBootSync, hideRadioAndVibesNoCompact, null, bootBackups);
        check("postSync clears temporary slot 1 back to Empty view", "Empty view", slotPkg(postSync.updatedMenuJson, 1));
        // Now unhide Light Vibes (keeping Radio hidden): Light Vibes MUST return to original slot 3, NOT slot 1!
        TileStore.Config unhideVibesOnly = new TileStore.Config(true, false, setOf("com.hkmc.radio/com.hkmc.radio.RadioActivity"));
        TileStore.SyncResult restoreVibes = TileStore.syncProfileMenu("Driver 1", postSync.updatedMenuJson, unhideVibesOnly, null, bootBackups);
        check("slot 1 stays Empty view (reserved for Radio)", "Empty view", slotPkg(restoreVibes.updatedMenuJson, 1));
        check("Light Vibes restored to exact original slot 3", "dev.dibble.lightvibes", slotPkg(restoreVibes.updatedMenuJson, 3));

        // 13. HMG-API state.json + mods/<id>/mod.json discovery
        File tmpDir = new File(System.getProperty("java.io.tmpdir"), "tilestore-mods-" + System.nanoTime());
        File modsRoot = new File(tmpDir, "mods");
        new File(modsRoot, "hmgapi-comfort").mkdirs();
        new File(modsRoot, "disabled-mod").mkdirs();
        File stateFile = new File(tmpDir, "state.json");
        TileStore.writeAtomic(stateFile, "{\"mods\":{"
                + "\"hmgapi-comfort\":{\"enabled\":true,\"apps\":[\"dev.lemoyne.comfort\"]},"
                + "\"disabled-mod\":{\"enabled\":false}"
                + "}}");
        TileStore.writeAtomic(new File(new File(modsRoot, "hmgapi-comfort"), "mod.json"),
                "{\"id\":\"hmgapi-comfort\",\"provides\":{\"launcher\":[{\"package\":\"dev.lemoyne.comfort\",\"class\":\".MainActivity\",\"title\":\"Comfort\"}]}}");
        TileStore.writeAtomic(new File(new File(modsRoot, "disabled-mod"), "mod.json"),
                "{\"id\":\"disabled-mod\",\"provides\":{\"launcher\":[{\"package\":\"dev.example.off\",\"class\":\".Main\",\"title\":\"Off\"}]}}");
        List<TileStore.ModLauncherSpec> specs = TileStore.readHmgApiLauncherMods(stateFile, modsRoot);
        check("readHmgApiLauncherMods finds only enabled mod", Integer.valueOf(1), Integer.valueOf(specs.size()));
        check("readHmgApiLauncherMods expands shorthand class", "dev.lemoyne.comfort/dev.lemoyne.comfort.MainActivity", specs.get(0).key);
        check("readHmgApiLauncherMods reads title", "Comfort", specs.get(0).title);

        System.out.println("TileStoreTest: " + checks + " checks passed");
    }

    /** Mock obfuscated Launcher_WP3 a/Q class with multiple constructor overloads. */
    @SuppressWarnings("unused")
    private static final class MockQ {
        private int zMode;
        private String aPkg;
        private String bCls;
        private int cIcon;
        private int dIconDis;
        private int eTitleId;
        private String fCachedLabel;
        private int gBadge;

        static MockQ ctor7(String pkg, String cls, int icon, int iconDis, int titleId, String label) {
            MockQ q = new MockQ();
            q.aPkg = pkg;
            q.bCls = cls;
            q.cIcon = icon;
            q.dIconDis = iconDis;
            q.eTitleId = titleId;
            q.fCachedLabel = label;
            return q;
        }

        static MockQ ctor8Badge(String pkg, String cls, int icon, int iconDis, int titleId, int badge) {
            MockQ q = new MockQ();
            q.aPkg = pkg;
            q.bCls = cls;
            q.cIcon = icon;
            q.dIconDis = iconDis;
            q.eTitleId = titleId;
            q.gBadge = badge;
            return q;
        }

        static MockQ ctor6PkgOnly(String pkg, int icon, int iconDis, int titleId) {
            MockQ q = new MockQ();
            q.aPkg = pkg;
            q.bCls = "";
            q.cIcon = icon;
            q.dIconDis = iconDis;
            q.eTitleId = titleId;
            return q;
        }

        static MockQ ctorCustomLabel(String pkg, String cls, String customLabel) {
            MockQ q = new MockQ();
            q.aPkg = pkg;
            q.bCls = cls;
            q.eTitleId = 0;
            q.fCachedLabel = customLabel;
            return q;
        }
    }
}
