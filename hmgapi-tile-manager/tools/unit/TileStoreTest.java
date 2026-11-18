package dev.lemoyne.tiles.hook;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
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
        Set<String> multiPkgs = setOf("com.android.settings");

        // Equipped stock tiles on this US Ioniq 5 MUST pass
        checkTrue("EV equipped", TileStore.isEquippedSystemTile(
                "com.hkmc.ev/com.hkmc.ev.EvActivity", "com.hkmc.ev", ".EvActivity", i5Inv, multiPkgs));
        checkTrue("Navigation Menu (pkg-only) equipped", TileStore.isEquippedSystemTile(
                "com.mnsoft.navi/com.mnsoft.navi.NaviMenuActivity", "com.mnsoft.navi", ".NaviMenuActivity", i5Inv, multiPkgs));
        checkTrue("Hyundai Pay equipped", TileStore.isEquippedSystemTile(
                "com.hkmc.carpay/com.hkmc.carpay.CarPayActivity", "com.hkmc.carpay", ".CarPayActivity", i5Inv, multiPkgs));
        checkTrue("Radio (@class) equipped", TileStore.isEquippedSystemTile(
                "@class/Radio", "", "Radio", i5Inv, multiPkgs));
        checkTrue("Setup (com.android.settings.Settings) equipped", TileStore.isEquippedSystemTile(
                "com.android.settings/com.android.settings.Settings", "com.android.settings", "com.android.settings.Settings", i5Inv, multiPkgs));

        // Unequipped trim/region ghost entries MUST be excluded (including Memory Seat sharing com.android.settings!)
        check("Built-in Cam excluded", Boolean.FALSE, Boolean.valueOf(TileStore.isEquippedSystemTile(
                "com.hkmc.dvrs/com.hkmc.dvrs.MainActivity", "com.hkmc.dvrs", ".MainActivity", i5Inv, multiPkgs)));
        check("Memory Seat Configuration excluded despite sharing com.android.settings", Boolean.FALSE, Boolean.valueOf(
                TileStore.isEquippedSystemTile("com.android.settings/com.android.settings.ImsSeatActivity",
                        "com.android.settings", "com.android.settings.ImsSeatActivity", i5Inv, multiPkgs)));
        check("Sound Mood Lamp excluded", Boolean.FALSE, Boolean.valueOf(TileStore.isEquippedSystemTile(
                "com.hkmc.moodlamp/com.hkmc.moodlamp.MoodLampActivity", "com.hkmc.moodlamp", ".MoodLampActivity", i5Inv, multiPkgs)));
        check("VIBE excluded", Boolean.FALSE, Boolean.valueOf(TileStore.isEquippedSystemTile(
                "com.naver.vibe/com.naver.vibe.MainActivity", "com.naver.vibe", ".MainActivity", i5Inv, multiPkgs)));

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
        TileStore.EntryInspector.Extracted exNotif = inspector.extract(notifEntry, i5Inv, Collections.<String>emptySet(), mockRes);
        check("8-arg Notifications pkg", "com.hkmc.notification", exNotif.pkg);
        check("8-arg Notifications cls", ".NoticeListActivity", exNotif.cls);
        check("8-arg Notifications titleId", Integer.valueOf(0x7f080021), Integer.valueOf(exNotif.titleId));

        // 6-arg package-only constructor (Navigation Menu)
        MockQ naviEntry = MockQ.ctor6PkgOnly("com.mnsoft.navi", 0x7f020012, 0x7f020013, 0x7f080022);
        TileStore.EntryInspector.Extracted exNavi = inspector.extract(naviEntry, i5Inv, Collections.<String>emptySet(), mockRes);
        check("6-arg Navigation Menu pkg", "com.mnsoft.navi", exNavi.pkg);
        check("6-arg Navigation Menu key", "com.mnsoft.navi/*", TileStore.normKey(exNavi.pkg, exNavi.cls));

        // Custom/NEMESIS direct-title constructor (titleId = 0)
        MockQ nemesisEntry = MockQ.ctorCustomLabel("com.nemesis.manager", "com.nemesis.manager.MainActivity", "NEMESIS Manager");
        TileStore.EntryInspector.Extracted exNem = inspector.extract(nemesisEntry, i5Inv, Collections.<String>emptySet(), mockRes);
        check("Custom label pkg", "com.nemesis.manager", exNem.pkg);
        check("Custom label titleText", "NEMESIS Manager", exNem.titleText);
        check("NEMESIS package classified as package", "package",
                TileStore.classifyTileKind(exNem.pkg, exNem.titleText, 0, -1, false, "nemesis"));

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

        // 14. v1.0.2 regressions: OEM /system/app classification regardless of vendor package prefix
        //     (Hyundai Pay, Notifications, Valet Mode, Phone Projection) vs custom /data/app & mods
        check("Hyundai Pay (non-hkmc /system/app) is system", "system",
                TileStore.classifyTileKind("com.hmc.carpay", "Hyundai Pay", 0x7f080050, 5, false, null));
        check("Notifications (non-hkmc /system/app) is system", "system",
                TileStore.classifyTileKind("com.ivi.notification.center", "Notifications", 0x7f080051, 12, false, null));
        check("Valet Mode (non-hkmc /system/app) is system", "system",
                TileStore.classifyTileKind("com.hmc.valetmode", "Valet Mode", 0x7f080052, 16, false, null));
        check("Phone Projection (non-hkmc /system/app) is system", "system",
                TileStore.classifyTileKind("com.connectivity.projection", "Phone Projection", 0x7f080053, 6, false, null));
        check("Sideloaded /data/app APK is package", "package",
                TileStore.classifyTileKind("org.videolan.vlc", "VLC", 0, -1, true, null));
        check("HMG-API mod with 0x7f7e0000 titleId is package", "package",
                TileStore.classifyTileKind("dev.lemoyne.comfort", "Comfort", 0x7f7e0003, -1, true, "hmgapi-comfort"));
        check("HMG-API core app is package", "package",
                TileStore.classifyTileKind("hmgapi.app", "HMG-API", 0, 9000, true, "hmgapi"));

        // 15. v1.0.2 regressions: Gen5wFacts hardware/region feature gate + multi-entry Settings wildcard guard
        File permDir = new File(tmpDir, "permissions");
        permDir.mkdirs();
        TileStore.writeAtomic(new File(permDir, "car.xml"),
                "<permissions><feature name=\"com.hkmc.software.carpay\"/><feature name=\"android.hardware.location\"/></permissions>");
        Set<String> parsedFeats = TileStore.readPermissionXmlFeatures(permDir);
        checkTrue("readPermissionXmlFeatures parses carpay", parsedFeats.contains("com.hkmc.software.carpay"));

        TileStore.Gen5wFacts usIoniq5Se = new TileStore.Gen5wFacts(
                "NEEV23SE.USA.260903", 1, parsedFeats, null, null, null);
        check("US Ioniq 5 SE excludes VIBE", Boolean.FALSE,
                Boolean.valueOf(TileStore.isSupportedStockFeature("VIBE", "com.naver.vibe", ".MainActivity", usIoniq5Se)));
        check("US Ioniq 5 SE excludes Built-in Cam", Boolean.FALSE,
                Boolean.valueOf(TileStore.isSupportedStockFeature("Built-in Cam", "com.hkmc.dvrs", ".DvrsActivity", usIoniq5Se)));
        check("US Ioniq 5 SE excludes Sound Mood Lamp", Boolean.FALSE,
                Boolean.valueOf(TileStore.isSupportedStockFeature("Sound Mood Lamp", "com.android.settings", ".SoundMoodLampActivity", usIoniq5Se)));
        check("US Ioniq 5 SE excludes Memory Seat Configuration", Boolean.FALSE,
                Boolean.valueOf(TileStore.isSupportedStockFeature("Memory Seat Configuration", "com.android.settings", ".ImsSeatActivity", usIoniq5Se)));

        // All real stock tiles on US Ioniq 5 SE MUST pass Gen5wFacts
        for (String[] real : new String[][] {
                { "EV", "com.hkmc.ev", ".EvActivity" },
                { "Navigation Menu", "com.mnsoft.navi", "" },
                { "Hyundai Pay", "com.hmc.carpay", ".CarPayActivity" },
                { "Radio", "", "Radio" },
                { "Bluelink", "com.hkmc.telematics.bluelink", ".Main" },
                { "Notifications", "com.ivi.notification", ".NoticeActivity" },
                { "User's Manual", "com.hkmc.qrmanual", "" },
                { "Valet Mode", "com.hmc.valetmode", ".ValetActivity" },
                { "Phone Projection", "com.connectivity.projection", ".MainActivity" },
                { "Quiet Mode", "com.hkmc.quietmode", ".QuietActivity" },
                { "Setup", "com.android.settings", "com.android.settings.Settings" }
        }) {
            checkTrue("US Ioniq 5 SE keeps real stock tile " + real[0],
                    TileStore.isSupportedStockFeature(real[0], real[1], real[2], usIoniq5Se));
        }

        // Equipped trims/regions DO keep their supported features
        TileStore.Gen5wFacts korCarWithIms = new TileStore.Gen5wFacts(
                "NEEV23.KOR.260903", 0, setOf("com.hkmc.software.ims"), Integer.valueOf(1), Integer.valueOf(1), Integer.valueOf(1));
        checkTrue("KOR car keeps VIBE", TileStore.isSupportedStockFeature("VIBE", "com.naver.vibe", ".MainActivity", korCarWithIms));
        checkTrue("KOR car with DVRS keeps Built-in Cam", TileStore.isSupportedStockFeature("Built-in Cam", "com.hkmc.dvrs", ".DvrsActivity", korCarWithIms));
        checkTrue("IMS option=1 keeps Memory Seat Configuration", TileStore.isSupportedStockFeature("Memory Seat Configuration", "com.android.settings", ".ImsSeatActivity", korCarWithIms));
        checkTrue("SML option=1 keeps Sound Mood Lamp", TileStore.isSupportedStockFeature("Sound Mood Lamp", "com.hkmc.sml", ".SmlActivity", korCarWithIms));

        // Even if com.android.settings/* is in equippedKeys, sub-screens like Memory Seat do NOT false-match, while Setup DOES!
        String pkgOnlySettingsMenu = "{\"menuItems\":[{\"packageName\":\"com.android.settings\",\"itemId\":20,\"className\":\"\"}]}";
        TileStore.MenuInventory pkgOnlySettingsInv = TileStore.collectMenuInventory(
                Collections.singletonList(pkgOnlySettingsMenu), bootBackups);
        check("com.android.settings/* does NOT false-match Memory Seat sub-activity", Boolean.FALSE, Boolean.valueOf(
                TileStore.isEquippedSystemTile("com.android.settings/com.android.settings.ImsSeatActivity",
                        "com.android.settings", "com.android.settings.ImsSeatActivity", pkgOnlySettingsInv, multiPkgs)));
        checkTrue("com.android.settings/* still keeps main Setup activity",
                TileStore.isEquippedSystemTile("com.android.settings/com.android.settings.Settings",
                        "com.android.settings", "com.android.settings.Settings", pkgOnlySettingsInv, multiPkgs));
        // Real hidden Radio backed up in bootBackups stays equipped even when absent from active menuItems!
        checkTrue("Backed-up hidden Radio stays equipped",
                TileStore.isEquippedSystemTile("com.hkmc.radio/com.hkmc.radio.RadioActivity",
                        "com.hkmc.radio", "com.hkmc.radio.RadioActivity", pkgOnlySettingsInv, multiPkgs));

        // 16. v1.0.3 regressions: schema-5 parity, wildcard backup/equip/restore, EntryInspector
        //     wildcard-matched itemId disambiguation, and renamed owner profile selection
        check("TileStore.CATALOG_SCHEMA is 5", Integer.valueOf(5), Integer.valueOf(TileStore.CATALOG_SCHEMA));

        Map<String, Object> qrBackups = new LinkedHashMap<String, Object>();
        TileStore.Config hideQrManual = new TileStore.Config(true, true, setOf("com.hkmc.qrmanual/*"));
        TileStore.SyncResult rHideQr = TileStore.syncProfileMenu("Driver 1", i5Menu, hideQrManual, null, qrBackups);
        checkTrue("hiding pkg/* stock tile updates menu", rHideQr.updatedMenuJson != null);
        TileStore.MenuInventory qrHiddenInv = TileStore.collectMenuInventory(
                Collections.singletonList(rHideQr.updatedMenuJson), qrBackups);
        checkTrue("backedUpKeys retains hidden pkg/* key", qrHiddenInv.backedUpKeys.contains("com.hkmc.qrmanual/*"));
        checkTrue("hidden pkg/* tile stays equipped when Q reports full pkg/cls",
                TileStore.isEquippedSystemTile("com.hkmc.qrmanual/com.hkmc.qrmanual.QRManualActivity",
                        "com.hkmc.qrmanual", "com.hkmc.qrmanual.QRManualActivity", qrHiddenInv, multiPkgs));
        check("hidden pkg/* matches full pkg/cls Q entry", "com.hkmc.qrmanual/*",
                TileStore.matchingHiddenKey(hideQrManual.hidden,
                        "com.hkmc.qrmanual", "com.hkmc.qrmanual.QRManualActivity", 15));
        TileStore.SyncResult rShowQr = TileStore.syncProfileMenu("Driver 1", rHideQr.updatedMenuJson, showAll, null, qrBackups);
        check("Show All restores User's Manual into first free slot (slot 7)", "com.hkmc.qrmanual", slotPkg(rShowQr.updatedMenuJson, 7));
        check("Show All preserves User's Manual itemId 15", Integer.valueOf(15), Integer.valueOf(slotItemId(rShowQr.updatedMenuJson, 7)));

        // EntryInspector Step 4: zMode=2 must NOT collide with ALL_MENU itemId 2 (com.mnsoft.navi/*)
        // once full-class com.mnsoft.navi/com.mnsoft.navi.NaviMenuActivity is already matched!
        TileStore.IntStringProbe radioProbe = new TileStore.IntStringProbe() {
            @Override
            public String resolveStringResource(int resId) {
                if (resId == 0x7f080024) return "Radio";
                return mockRes.resolveStringResource(resId);
            }
        };
        MockQ radioById = MockQ.ctorItemIdOnly(2, 8, 0x7f080024);
        Set<String> matchedFullClass = setOf("com.mnsoft.navi/com.mnsoft.navi.NaviMenuActivity");
        TileStore.EntryInspector.Extracted exRadio = inspector.extract(radioById, i5Inv, matchedFullClass, radioProbe);
        check("itemId-only Q entry ignores already-matched pkg/* collision on zMode=2",
                Integer.valueOf(8), Integer.valueOf(exRadio.matchedItemId));
        check("itemId-only Q entry resolves Radio virtual class", "Radio", exRadio.cls);
        check("itemId-only Q entry preserves calibrated titleId", Integer.valueOf(0x7f080024), Integer.valueOf(exRadio.titleId));

        // Renamed Bluelink/Kia Connect profile ("Alex") is preferred over unpruned factory Driver 2 / Guest rows
        String unprunedFactoryProfile = "{\"menuItems\":[{\"packageName\":\"com.naver.vibe\",\"itemId\":31,\"className\":\".MainActivity\"}]}";
        Map<String, String> renamedProfiles = new LinkedHashMap<String, String>();
        renamedProfiles.put("Alex", i5Menu);
        renamedProfiles.put("Driver 2", unprunedFactoryProfile);
        renamedProfiles.put("Guest", unprunedFactoryProfile);
        List<String> selectedRenamed = TileStore.selectProfileMenuJsons(renamedProfiles);
        check("renamed owner profile selects only Alex (omits factory Driver 2/Guest)",
                Integer.valueOf(1), Integer.valueOf(selectedRenamed.size()));
        check("renamed owner profile returns Alex menu JSON", i5Menu, selectedRenamed.get(0));

        Map<String, String> defaultProfiles = new LinkedHashMap<String, String>();
        defaultProfiles.put("Driver 1", i5Menu);
        defaultProfiles.put("Driver 2", unprunedFactoryProfile);
        defaultProfiles.put("Guest", unprunedFactoryProfile);
        List<String> selectedDefault = TileStore.selectProfileMenuJsons(defaultProfiles);
        check("default profiles select only Driver 1", Integer.valueOf(1), Integer.valueOf(selectedDefault.size()));
        check("default profiles return Driver 1 menu JSON", i5Menu, selectedDefault.get(0));

        Map<String, String> twoCustomProfiles = new LinkedHashMap<String, String>();
        twoCustomProfiles.put("Alex", i5Menu);
        twoCustomProfiles.put("Sam", sampleGrid());
        twoCustomProfiles.put("Guest", unprunedFactoryProfile);
        List<String> selectedTwoCustom = TileStore.selectProfileMenuJsons(twoCustomProfiles);
        check("two custom driver profiles keep both while omitting Guest",
                Integer.valueOf(2), Integer.valueOf(selectedTwoCustom.size()));

        // 17. v1.0.4 regressions: EntryInspector enabled iconId calibration & IntDrawableProbe disambiguation
        check("8-arg Notifications extracts enabled iconId (not disabled 0x7f020011)",
                Integer.valueOf(0x7f020010), Integer.valueOf(exNotif.iconId));
        check("6-arg Navigation Menu extracts enabled iconId (not disabled 0x7f020013)",
                Integer.valueOf(0x7f020012), Integer.valueOf(exNavi.iconId));

        TileStore.EntryInspector uncalInspector = new TileStore.EntryInspector();
        MockQ radioWithIcons = MockQ.ctor8Badge("com.hkmc.radio", ".RadioActivity",
                0x7f020041, 0x7f020042, 0x7f080024, 0);
        // Swap order so disabled icon appears in an earlier field or probe both:
        TileStore.IntDrawableProbe drawProbe = new TileStore.IntDrawableProbe() {
            @Override
            public String resolveDrawableEntryName(int resId) {
                if (resId == 0x7f020041) return "hom_radio_66_dis";
                if (resId == 0x7f020042) return "hom_radio_66";
                return null;
            }
        };
        TileStore.EntryInspector.Extracted exProbe = uncalInspector.extract(
                radioWithIcons, i5Inv, Collections.<String>emptySet(), radioProbe, drawProbe);
        check("IntDrawableProbe skips _dis icon and picks enabled hom_radio_66",
                Integer.valueOf(0x7f020042), Integer.valueOf(exProbe.iconId));

        // 18. v1.0.5 regressions: core stock self-healing, cross-representation backup & equip
        //     matching, multi-profile owner union, and Show All / unhide orphan re-seating
        for (String[] core : new String[][] {
                { "Setup", "com.android.settings", "com.android.settings.Settings" },
                { "Radio", "", "Radio" },
                { "Radio", "com.hkmc.radio", "com.hkmc.radio.RadioActivity" },
                { "Media", "com.hkmc.media", "com.hkmc.media.MediaActivity" },
                { "Phone", "com.hkmc.phone", "com.hkmc.phone.PhoneActivity" },
                { "Phone Projection", "com.connectivity.projection", "com.connectivity.projection.MainActivity" },
                { "Voice Memo", "com.hkmc.voicememo", "com.hkmc.voicememo.MainActivity" },
                { "User's Manual", "com.hkmc.qrmanual", "com.hkmc.qrmanual.QRManualActivity" },
                { "Notifications", "com.ivi.notification", "com.ivi.notification.NoticeActivity" },
                { "Navigation Menu", "com.mnsoft.navi", "com.mnsoft.navi.NaviMenuActivity" }
        }) {
            checkTrue("isUnconditionalCoreStockTile accepts universal " + core[0],
                    TileStore.isUnconditionalCoreStockTile(core[0], core[1], core[2]));
        }
        for (String[] nonCore : new String[][] {
                { "VIBE", "com.naver.vibe", "com.naver.vibe.MainActivity" },
                { "Built-in Cam", "com.hkmc.dvrs", "com.hkmc.dvrs.DvrsActivity" },
                { "Sound Mood Lamp", "com.hkmc.soundmood", "com.hkmc.soundmood.MainActivity" },
                { "Memory Seat Configuration", "com.android.settings", "com.android.settings.ImsSeatActivity" },
                { "EV", "com.hkmc.ev", "com.hkmc.ev.EvActivity" },
                { "Hyundai Pay", "com.hmc.carpay", "com.hmc.carpay.CarPayActivity" },
                { "Bluelink", "com.hkmc.telematics.bluelink", "com.hkmc.telematics.bluelink.Main" },
                { "Valet Mode", "com.hmc.valetmode", "com.hmc.valetmode.ValetActivity" },
                { "HD Radio Data", "com.hkmc.hdradio", "com.hkmc.hdradio.MainActivity" },
                { "SiriusXM", "com.siriusxm.app", "com.siriusxm.app.MainActivity" }
        }) {
            check("isUnconditionalCoreStockTile rejects optional/trim " + nonCore[0], Boolean.FALSE,
                    Boolean.valueOf(TileStore.isUnconditionalCoreStockTile(nonCore[0], nonCore[1], nonCore[2])));
        }

        // Cross-representation isEquippedSystemTile matching (single-package class difference, @class/Radio, itemId)
        checkTrue("com.hkmc.radio/.RadioActivity matches @class/Radio in all_menu",
                TileStore.isEquippedSystemTile("com.hkmc.radio/com.hkmc.radio.RadioActivity",
                        "com.hkmc.radio", "com.hkmc.radio.RadioActivity", -1, i5Inv, multiPkgs));
        checkTrue("single-tile package matches alternate activity class in all_menu",
                TileStore.isEquippedSystemTile("com.hkmc.qrmanual/com.hkmc.qrmanual.WebManualActivity",
                        "com.hkmc.qrmanual", "com.hkmc.qrmanual.WebManualActivity", -1, i5Inv, multiPkgs));
        checkTrue("itemId correlation marks system tile equipped",
                TileStore.isEquippedSystemTile("com.unknown.oem/com.unknown.oem.MainActivity",
                        "com.unknown.oem", "com.unknown.oem.MainActivity", 8, i5Inv, multiPkgs));
        check("Memory Seat sub-activity stays rejected under cross-representation check", Boolean.FALSE,
                Boolean.valueOf(TileStore.isEquippedSystemTile(
                        "com.android.settings/com.android.settings.ImsSeatActivity",
                        "com.android.settings", "com.android.settings.ImsSeatActivity", -1, i5Inv, multiPkgs)));

        // @class/Radio backup MUST NOT be dropped when installedKeys contains com.hkmc.radio/.RadioActivity
        Map<String, Object> radioBak = new LinkedHashMap<String, Object>();
        TileStore.Config hideVirtualRadio = new TileStore.Config(true, false, setOf("@class/Radio"));
        TileStore.SyncResult rHideVirtualRadio = TileStore.syncProfileMenu("Driver 1", i5Menu, hideVirtualRadio, null, radioBak);
        checkTrue("hiding @class/Radio updates menu", rHideVirtualRadio.updatedMenuJson != null);
        Set<String> installedPkgRadioOnly = setOf("com.hkmc.radio/com.hkmc.radio.RadioActivity");
        TileStore.SyncResult rRestoreVirtualRadio = TileStore.syncProfileMenu(
                "Driver 1", rHideVirtualRadio.updatedMenuJson, showAll, installedPkgRadioOnly, radioBak);
        checkTrue("@class/Radio restores instead of dropping backup", rRestoreVirtualRadio.updatedMenuJson != null);
        checkTrue("restored menu contains Radio class", rRestoreVirtualRadio.updatedMenuJson.contains("\"className\":\"Radio\""));

        // Multi-profile owner union (Driver 1 + custom-named Sam) + empty-profile fallback + Driver 2 custom APK discovery
        Map<String, String> mixedOwners = new LinkedHashMap<String, String>();
        String driver2WithCustomApk = "{\"menuItems\":["
                + "{\"packageName\":\"com.naver.vibe\",\"itemId\":31,\"className\":\".MainActivity\"},"
                + "{\"packageName\":\"org.videolan.vlc\",\"itemId\":1,\"className\":\"org.videolan.vlc.StartActivity\"}"
                + "]}";
        mixedOwners.put("Driver1", sampleGrid());
        mixedOwners.put("Sam", i5Menu);
        mixedOwners.put("Driver 2", driver2WithCustomApk);
        mixedOwners.put("Guest", unprunedFactoryProfile);
        List<String> selectedMixed = TileStore.selectProfileMenuJsons(mixedOwners);
        check("Driver1 + Sam unions both owner profiles (omits Driver 2 & Guest)",
                Integer.valueOf(2), Integer.valueOf(selectedMixed.size()));
        TileStore.MenuInventory mixedInv = TileStore.collectMenuInventory(
                selectedMixed, TileStore.collectNonGuestMenuJsons(mixedOwners), null);
        check("unpruned Driver 2 VIBE excluded from equippedKeys", Boolean.FALSE,
                Boolean.valueOf(mixedInv.equippedKeys.contains("com.naver.vibe/com.naver.vibe.MainActivity")));
        boolean foundDriver2Vlc = false;
        for (TileStore.MenuEntryRecord rec : mixedInv.allNonGuestEntries) {
            if ("org.videolan.vlc".equals(rec.packageName)) foundDriver2Vlc = true;
        }
        checkTrue("Driver 2 custom APK discovered in allNonGuestEntries", foundDriver2Vlc);

        Map<String, String> emptyDriver1Profiles = new LinkedHashMap<String, String>();
        emptyDriver1Profiles.put("Driver 1",
                "{\"menuItems\":[{\"packageName\":\"Empty view\",\"itemId\":39,\"className\":\"Empty view\"}]}");
        emptyDriver1Profiles.put("Driver 2", i5Menu);
        List<String> fallbackToDriver2 = TileStore.selectProfileMenuJsons(emptyDriver1Profiles);
        check("empty Driver 1 falls back to non-Guest profiles",
                Integer.valueOf(2), Integer.valueOf(fallbackToDriver2.size()));

        // Orphan re-seating (Show All / single-tile unhide when a prior version left Empty view without backup.json)
        String orphanedMenu = "{\"menuItems\":["
                + "{\"packageName\":\"\",\"itemId\":8,\"className\":\"Radio\"},"
                + "{\"packageName\":\"com.mnsoft.navi\",\"itemId\":2,\"className\":\"\"},"
                + "{\"packageName\":\"Empty view\",\"itemId\":39,\"className\":\"Empty view\"},"
                + "{\"packageName\":\"com.android.settings\",\"itemId\":20,\"className\":\"\"},"
                + "{\"packageName\":\"Empty view\",\"itemId\":39,\"className\":\"Empty view\"}"
                + "]}";
        List<TileStore.ReconcileTile> reconcileAll = new ArrayList<TileStore.ReconcileTile>();
        // Already placed via @class/Radio, com.mnsoft.navi/*, and com.android.settings/* -> MUST NOT duplicate!
        reconcileAll.add(new TileStore.ReconcileTile(
                "com.hkmc.radio/com.hkmc.radio.RadioActivity", "com.hkmc.radio", "com.hkmc.radio.RadioActivity", "Radio", -1));
        reconcileAll.add(new TileStore.ReconcileTile(
                "com.mnsoft.navi/com.mnsoft.navi.NaviMenuActivity", "com.mnsoft.navi", "com.mnsoft.navi.NaviMenuActivity", "Navigation Menu", -1));
        reconcileAll.add(new TileStore.ReconcileTile(
                "com.android.settings/com.android.settings.Settings", "com.android.settings", "com.android.settings.Settings", "Setup", -1));
        // Truly orphaned tiles missing from both all_menu and backup.json -> MUST re-seat into Empty view slots!
        reconcileAll.add(new TileStore.ReconcileTile(
                "com.hkmc.qrmanual/com.hkmc.qrmanual.QRManualActivity", "com.hkmc.qrmanual", "com.hkmc.qrmanual.QRManualActivity", "User's Manual", 15));
        reconcileAll.add(new TileStore.ReconcileTile(
                "com.ivi.notification/com.ivi.notification.NoticeActivity", "com.ivi.notification", "com.ivi.notification.NoticeActivity", "Notifications", 12));

        Map<String, Object> emptyBackups = new LinkedHashMap<String, Object>();
        TileStore.InstallChecker allInstalled = new TileStore.InstallChecker() {
            @Override
            public boolean isBackupStillInstalled(String backupKey, String pkg, String cls, int itemId) {
                return true;
            }
        };
        TileStore.Config showAllNoCompact = new TileStore.Config(true, false, Collections.<String>emptySet());
        TileStore.SyncResult reseated = TileStore.syncProfileMenu(
                "Driver 1", orphanedMenu, showAllNoCompact, allInstalled, emptyBackups, reconcileAll, multiPkgs);
        checkTrue("Show All re-seats orphaned tiles even when backup.json is empty", reseated.updatedMenuJson != null);
        check("slot 0 remains Radio (not duplicated)", "", slotPkg(reseated.updatedMenuJson, 0));
        check("slot 1 remains Navigation Menu", "com.mnsoft.navi", slotPkg(reseated.updatedMenuJson, 1));
        check("slot 2 re-seats orphaned User's Manual", "com.hkmc.qrmanual", slotPkg(reseated.updatedMenuJson, 2));
        check("slot 2 preserves itemId 15", Integer.valueOf(15), Integer.valueOf(slotItemId(reseated.updatedMenuJson, 2)));
        check("slot 3 remains Setup", "com.android.settings", slotPkg(reseated.updatedMenuJson, 3));
        check("slot 4 re-seats orphaned Notifications", "com.ivi.notification", slotPkg(reseated.updatedMenuJson, 4));

        // 19. v1.0.5 reordering: stock Edit Home Icons compatibility, event-driven gap compaction,
        //     FilterableMenuList order preservation across Dalvik/JDK mutations, and lossless reorderProfileMenu
        Map<String, Object> stockEditBackups = new LinkedHashMap<String, Object>();
        TileStore.Config hideRadioAndComfortCompact = new TileStore.Config(true, true, setOf(
                "com.hkmc.radio/com.hkmc.radio.RadioActivity",
                "dev.lemoyne.comfort/dev.lemoyne.comfort.MainActivity"));
        TileStore.SyncResult initialHideCompact = TileStore.syncProfileMenu(
                "Driver 1", sampleGrid(), hideRadioAndComfortCompact, installed, stockEditBackups);
        checkTrue("initial hide with compactGaps=true compacts grid", initialHideCompact.updatedMenuJson != null);

        // User opens stock Edit Home Icons, swaps HMG-API before EV, and leaves an intentional
        // Empty view spacer in slot 1 (e.g. pushing EV to the next page/column).
        String stockEditedWithSpacer = "{\"menuItems\":["
                + "{\"packageName\":\"hmgapi.app\",\"itemId\":9000,\"className\":\"hmgapi.app.MainActivity\"},"
                + "{\"packageName\":\"Empty view\",\"itemId\":39,\"className\":\"Empty view\"},"
                + "{\"packageName\":\"com.hkmc.ev\",\"itemId\":3,\"className\":\"com.hkmc.ev.EvActivity\"},"
                + "{\"packageName\":\"Empty view\",\"itemId\":39,\"className\":\"Empty view\"},"
                + "{\"packageName\":\"Empty view\",\"itemId\":39,\"className\":\"Empty view\"}"
                + "]}";
        TileStore.SyncResult passiveReload = TileStore.syncProfileMenu(
                "Driver 1", stockEditedWithSpacer, hideRadioAndComfortCompact, installed, stockEditBackups);
        check("passive Launcher_WP3 reload preserves stock Edit Home Icons order & multi-page spacer",
                null, passiveReload.updatedMenuJson);

        // On reboot, HmgApi.syncSavedMenu temporarily re-inserts already-backed-up hidden Comfort (9001)
        // into trailing slot 3; syncProfileMenu must clear ONLY slot 3 without collapsing slot 1's spacer!
        String rebootTransientModInsert = "{\"menuItems\":["
                + "{\"packageName\":\"hmgapi.app\",\"itemId\":9000,\"className\":\"hmgapi.app.MainActivity\"},"
                + "{\"packageName\":\"Empty view\",\"itemId\":39,\"className\":\"Empty view\"},"
                + "{\"packageName\":\"com.hkmc.ev\",\"itemId\":3,\"className\":\"com.hkmc.ev.EvActivity\"},"
                + "{\"packageName\":\"dev.lemoyne.comfort\",\"itemId\":9001,\"className\":\"dev.lemoyne.comfort.MainActivity\"},"
                + "{\"packageName\":\"Empty view\",\"itemId\":39,\"className\":\"Empty view\"}"
                + "]}";
        TileStore.SyncResult rebootCleared = TileStore.syncProfileMenu(
                "Driver 1", rebootTransientModInsert, hideRadioAndComfortCompact, installed, stockEditBackups);
        checkTrue("reboot transient hidden mod slot is cleared", rebootCleared.updatedMenuJson != null);
        check("reboot preserves HMG-API at slot 0", "hmgapi.app", slotPkg(rebootCleared.updatedMenuJson, 0));
        check("reboot preserves intentional Empty view spacer at slot 1",
                "Empty view", slotPkg(rebootCleared.updatedMenuJson, 1));
        check("reboot preserves EV at slot 2", "com.hkmc.ev", slotPkg(rebootCleared.updatedMenuJson, 2));
        check("reboot clears transient Comfort at slot 3", "Empty view", slotPkg(rebootCleared.updatedMenuJson, 3));

        // Explicit forceCompact=true (toggling Close Empty Gaps ON in Tile Manager) compacts gaps on demand
        TileStore.SyncResult explicitCompact = TileStore.syncProfileMenu(
                "Driver 1", stockEditedWithSpacer, hideRadioAndComfortCompact,
                null, stockEditBackups, null, null, true);
        checkTrue("forceCompact=true closes empty gaps on demand", explicitCompact.updatedMenuJson != null);
        check("forceCompact keeps swapped HMG-API first at slot 0",
                "hmgapi.app", slotPkg(explicitCompact.updatedMenuJson, 0));
        check("forceCompact packs EV left into slot 1",
                "com.hkmc.ev", slotPkg(explicitCompact.updatedMenuJson, 1));
        check("forceCompact trails Empty view at slot 2",
                "Empty view", slotPkg(explicitCompact.updatedMenuJson, 2));

        // FilterableMenuList order preservation across Collections.swap, remove+add, Dalvik ListIterator.set,
        // Collections.sort, clear+addAll, and identity deduplication while retaining hidden items
        final Set<String> orderHidden = new LinkedHashSet<String>(Collections.singletonList("B_HIDDEN"));
        TileStore.ItemVisibilityFilter orderFilter = new TileStore.ItemVisibilityFilter() {
            @Override
            public void onItemAdded(Object item) {}
            @Override
            public boolean shouldShow(Object item) {
                return !orderHidden.contains(String.valueOf(item));
            }
        };
        String tokA = new String("A_EV");
        String tokHidden = new String("B_HIDDEN");
        String tokC = new String("C_COMFORT");
        String tokD = new String("D_HMGAPI");
        TileStore.FilterableMenuList orderList = new TileStore.FilterableMenuList(
                Arrays.<Object>asList(tokA, tokHidden, tokC, tokD), orderFilter);
        check("initial visible order", Arrays.<Object>asList(tokA, tokC, tokD), new ArrayList<Object>(orderList));
        check("duplicate identity add ignored", Boolean.FALSE, Boolean.valueOf(orderList.add(tokA)));

        // 1) Collections.swap (invokes set(int, Object)) + refilter
        Collections.swap(orderList, 0, 2);
        orderList.refilter(orderFilter);
        check("Collections.swap order survives refilter",
                Arrays.<Object>asList(tokD, tokC, tokA), new ArrayList<Object>(orderList));
        checkTrue("hidden item retained in snapshotRaw after swap",
                orderList.snapshotRaw().contains(tokHidden));

        // 2) Drag-drop remove(int) + add(int, Object) + refilter
        Object dragged = orderList.remove(2); // tokA
        orderList.add(0, dragged);
        orderList.refilter(orderFilter);
        check("remove(int)+add(int,Object) order survives refilter",
                Arrays.<Object>asList(tokA, tokD, tokC), new ArrayList<Object>(orderList));
        checkTrue("hidden item retained in snapshotRaw after drag-insert",
                orderList.snapshotRaw().contains(tokHidden));

        // 3) Simulate Android 4.4 KitKat Dalvik Collections.sort via direct ListIterator.set mutations
        ListIterator<Object> lit = orderList.listIterator();
        lit.next();
        lit.set(tokC);
        lit.next();
        lit.set(tokA);
        lit.next();
        lit.set(tokD);
        orderList.refilter(orderFilter);
        check("ListIterator.set (Dalvik Collections.sort path) order survives refilter",
                Arrays.<Object>asList(tokC, tokA, tokD), new ArrayList<Object>(orderList));

        // Unhide B_HIDDEN: all 4 items appear with zero duplicates and C, A, D relative order preserved
        orderHidden.clear();
        orderList.refilter(orderFilter);
        check("unhiding restores all 4 items without duplicates",
                Integer.valueOf(4), Integer.valueOf(orderList.size()));
        check("unhiding preserves sorted relative order [tokC, tokA, tokHidden, tokD]",
                Arrays.<Object>asList(tokC, tokA, tokHidden, tokD), new ArrayList<Object>(orderList));

        // 4) Standard Collections.sort + refilter
        Collections.sort(orderList, new Comparator<Object>() {
            @Override
            public int compare(Object o1, Object o2) {
                return String.valueOf(o2).compareTo(String.valueOf(o1));
            }
        });
        orderList.refilter(orderFilter);
        check("Collections.sort descending survives refilter",
                Arrays.<Object>asList(tokD, tokC, tokHidden, tokA), new ArrayList<Object>(orderList));

        // 5) Adapter-style clear() + addAll(reorderedCopy)
        List<Object> reorderedCopy = Arrays.<Object>asList(tokA, tokD);
        orderList.clear();
        orderList.addAll(reorderedCopy);
        check("clear() + addAll() repopulates exact visible order",
                reorderedCopy, new ArrayList<Object>(orderList));

        // parseProfileSlots + lossless reorderProfileMenu (cross-representation keys, @empty spacers,
        // 9000+ mod IDs, unmentioned placed retention, overflow guard, and compactGaps stripping)
        String arrangeGrid = "{\"menuItems\":["
                + "{\"packageName\":\"\",\"itemId\":8,\"className\":\"Radio\"},"
                + "{\"packageName\":\"com.mnsoft.navi\",\"itemId\":2,\"className\":\"\"},"
                + "{\"packageName\":\"com.android.settings\",\"itemId\":20,\"className\":\"\"},"
                + "{\"packageName\":\"dev.lemoyne.comfort\",\"itemId\":9001,\"className\":\"dev.lemoyne.comfort.MainActivity\"},"
                + "{\"packageName\":\"Empty view\",\"itemId\":39,\"className\":\"Empty view\"},"
                + "{\"packageName\":\"Empty view\",\"itemId\":39,\"className\":\"Empty view\"}"
                + "]}";
        List<TileStore.ProfileSlot> parsedSlots = TileStore.parseProfileSlots(arrangeGrid);
        check("parseProfileSlots count", Integer.valueOf(6), Integer.valueOf(parsedSlots.size()));
        check("parseProfileSlots slot 0 virtual Radio key", "@class/Radio", parsedSlots.get(0).key);
        check("parseProfileSlots slot 1 pkg/* Navi key", "com.mnsoft.navi/*", parsedSlots.get(1).key);
        check("parseProfileSlots slot 4 @empty token", TileStore.EMPTY_SLOT_TOKEN, parsedSlots.get(4).key);
        checkTrue("parseProfileSlots slot 4 empty flag", parsedSlots.get(4).empty);

        // Reorder using catalog-form keys + intentional @empty spacer (Leaving Setup unmentioned to verify zero-loss!)
        List<String> desiredWithSpacer = Arrays.asList(
                "dev.lemoyne.comfort/dev.lemoyne.comfort.MainActivity",
                TileStore.EMPTY_SLOT_TOKEN,
                "com.mnsoft.navi/com.mnsoft.navi.NaviMenuActivity",
                "com.hkmc.radio/com.hkmc.radio.RadioActivity");
        TileStore.SyncResult reorderedSpaced = TileStore.reorderProfileMenu(
                "Driver 1", arrangeGrid, desiredWithSpacer, false);
        checkTrue("reorderProfileMenu produces updated JSON", reorderedSpaced.updatedMenuJson != null);
        check("reordered slot 0 is Comfort", "dev.lemoyne.comfort", slotPkg(reorderedSpaced.updatedMenuJson, 0));
        check("reordered slot 0 keeps mod itemId 9001",
                Integer.valueOf(9001), Integer.valueOf(slotItemId(reorderedSpaced.updatedMenuJson, 0)));
        check("reordered slot 1 respects @empty spacer",
                "Empty view", slotPkg(reorderedSpaced.updatedMenuJson, 1));
        check("reordered slot 2 matches pkg/* Navi from full catalog key",
                "com.mnsoft.navi", slotPkg(reorderedSpaced.updatedMenuJson, 2));
        check("reordered slot 2 preserves Navi itemId 2",
                Integer.valueOf(2), Integer.valueOf(slotItemId(reorderedSpaced.updatedMenuJson, 2)));
        check("reordered slot 3 matches @class/Radio from com.hkmc.radio catalog key",
                "", slotPkg(reorderedSpaced.updatedMenuJson, 3));
        check("reordered slot 3 preserves Radio itemId 8",
                Integer.valueOf(8), Integer.valueOf(slotItemId(reorderedSpaced.updatedMenuJson, 3)));
        check("reordered slot 4 retains unmentioned Setup without losing tile",
                "com.android.settings", slotPkg(reorderedSpaced.updatedMenuJson, 4));
        check("reordered slot 5 pads trailing Empty view",
                "Empty view", slotPkg(reorderedSpaced.updatedMenuJson, 5));

        // Idempotent no-op when desired order matches current layout
        List<String> sameOrderTokens = new ArrayList<String>();
        for (TileStore.ProfileSlot ps : TileStore.parseProfileSlots(reorderedSpaced.updatedMenuJson)) {
            sameOrderTokens.add(ps.key);
        }
        TileStore.SyncResult reorderNoOp = TileStore.reorderProfileMenu(
                "Driver 1", reorderedSpaced.updatedMenuJson, sameOrderTokens, false);
        check("reorderProfileMenu no-op returns null updatedMenuJson", null, reorderNoOp.updatedMenuJson);

        // Overflow guard: excessive @empty tokens cannot push placed tiles off a 6-slot grid
        List<String> overflowSpacers = Arrays.asList(
                TileStore.EMPTY_SLOT_TOKEN, TileStore.EMPTY_SLOT_TOKEN,
                TileStore.EMPTY_SLOT_TOKEN, TileStore.EMPTY_SLOT_TOKEN,
                "dev.lemoyne.comfort/dev.lemoyne.comfort.MainActivity");
        TileStore.SyncResult overflowSafe = TileStore.reorderProfileMenu(
                "Driver 1", arrangeGrid, overflowSpacers, false);
        List<TileStore.ProfileSlot> overflowSlots = TileStore.parseProfileSlots(overflowSafe.updatedMenuJson);
        check("overflow guard preserves 6 total slots", Integer.valueOf(6), Integer.valueOf(overflowSlots.size()));
        int placedAfterOverflow = 0;
        for (TileStore.ProfileSlot ps : overflowSlots) {
            if (!ps.empty) placedAfterOverflow++;
        }
        check("overflow guard loses zero placed tiles (all 4 kept)",
                Integer.valueOf(4), Integer.valueOf(placedAfterOverflow));

        // compactGaps=true strips @empty spacers and packs tiles tightly left
        TileStore.SyncResult reorderedCompacted = TileStore.reorderProfileMenu(
                "Driver 1", reorderedSpaced.updatedMenuJson, sameOrderTokens, true);
        checkTrue("reorderProfileMenu with compactGaps=true strips spacer", reorderedCompacted.updatedMenuJson != null);
        check("compacted reorder slot 0 Comfort", "dev.lemoyne.comfort", slotPkg(reorderedCompacted.updatedMenuJson, 0));
        check("compacted reorder slot 1 Navi (spacer stripped)", "com.mnsoft.navi", slotPkg(reorderedCompacted.updatedMenuJson, 1));
        check("compacted reorder slot 2 Radio", "", slotPkg(reorderedCompacted.updatedMenuJson, 2));
        check("compacted reorder slot 3 Setup", "com.android.settings", slotPkg(reorderedCompacted.updatedMenuJson, 3));
        check("compacted reorder slot 4 Empty view", "Empty view", slotPkg(reorderedCompacted.updatedMenuJson, 4));

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
        private int hItemId;

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

        static MockQ ctorItemIdOnly(int zModeFlag, int itemId, int titleId) {
            MockQ q = new MockQ();
            q.zMode = zModeFlag;
            q.aPkg = "";
            q.bCls = "";
            q.eTitleId = titleId;
            q.hItemId = itemId;
            return q;
        }
    }
}
