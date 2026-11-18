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

        // 16. v1.0.3 regressions: schema-4 parity, wildcard backup/equip/restore, EntryInspector
        //     wildcard-matched itemId disambiguation, and renamed owner profile selection
        check("TileStore.CATALOG_SCHEMA is 4", Integer.valueOf(4), Integer.valueOf(TileStore.CATALOG_SCHEMA));

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
