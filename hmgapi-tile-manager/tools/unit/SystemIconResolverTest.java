package dev.lemoyne.tiles;

import java.util.Arrays;
import java.util.List;

/**
 * Host JVM unit test verifying SystemIconResolver's Launcher_WP3 / HomeSetting_WP drawable
 * matching across all standard Hyundai/Kia/Genesis Gen5W stock tiles, ensuring enabled Home
 * Screen icons win over _dis/_prs/_bg/_widget variants and colliding feature names.
 */
public final class SystemIconResolverTest {
    private static int checks = 0;

    private static void check(String label, String expected, String actual) {
        checks++;
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("FAIL [" + label + "]: expected <" + expected + ">, got <" + actual + ">");
        }
    }

    private static TileWidgets.TileItem sys(String key, String pkg, String cls, String title) {
        return new TileWidgets.TileItem(key, pkg, cls, title, "system", null, false);
    }

    public static void main(String[] args) {
        List<String> launcherDrawables = Arrays.asList(
                // Distractors: disabled, pressed, backgrounds, widgets, split-screen, status bar
                "hom_ev_66_dis",
                "hom_ev_66_prs",
                "hom_bg_tile",
                "hom_widget_ev",
                "hom_split_navi",
                "statusbar_ico_phone",
                "hom_radio_66_dis",
                "hom_phone_66_d",
                "hom_setting_66_disabled",
                // Colliding sister features
                "hom_hev_66",
                "hom_phev_66",
                "hom_hdradio_66",
                "hom_sxm_66",
                "hom_ims_seat_66",
                "hom_sml_66",
                "hom_passenger_view_66",
                // Real enabled Gen5W Home Screen icons
                "hom_ev_66",
                "hom_navi_66",
                "hom_map_66",
                "hom_phone_66",
                "hom_phone_projection_66",
                "hom_radio_66",
                "hom_media_66",
                "hom_carpay_66",
                "hom_bluelink_66",
                "hom_uvo_66",
                "hom_noti_66",
                "hom_qrmanual_66",
                "hom_valet_66",
                "hom_quiet_66",
                "hom_voicememo_66",
                "hom_passenger_talk_66",
                "hom_climate_66",
                "hom_weather_66",
                "hom_setting_66"
        );

        check("EV picks hom_ev_66 (not _dis/_prs/hev/phev)", "hom_ev_66",
                SystemIconResolver.bestMatchingName(launcherDrawables,
                        sys("com.hkmc.ev/com.hkmc.ev.EvActivity", "com.hkmc.ev", ".EvActivity", "EV")));
        check("Navigation Menu picks hom_navi_66 (not map/split)", "hom_navi_66",
                SystemIconResolver.bestMatchingName(launcherDrawables,
                        sys("com.mnsoft.navi/*", "com.mnsoft.navi", "*", "Navigation Menu")));
        check("Map picks hom_map_66 (not navi)", "hom_map_66",
                SystemIconResolver.bestMatchingName(launcherDrawables,
                        sys("com.mnsoft.navi/.MapActivity", "com.mnsoft.navi", ".MapActivity", "Map")));
        check("Phone picks hom_phone_66 (not projection or _d)", "hom_phone_66",
                SystemIconResolver.bestMatchingName(launcherDrawables,
                        sys("com.hkmc.phone/.PhoneActivity", "com.hkmc.phone", ".PhoneActivity", "Phone")));
        check("Phone Projection picks hom_phone_projection_66 (not phone)", "hom_phone_projection_66",
                SystemIconResolver.bestMatchingName(launcherDrawables,
                        sys("com.connectivity.projection/.MainActivity", "com.connectivity.projection", ".MainActivity", "Phone Projection")));
        check("Radio (@class/Radio) picks hom_radio_66 (not hdradio/sxm/_dis)", "hom_radio_66",
                SystemIconResolver.bestMatchingName(launcherDrawables,
                        sys("@class/Radio", "", "Radio", "Radio")));
        check("HD Radio Data picks hom_hdradio_66", "hom_hdradio_66",
                SystemIconResolver.bestMatchingName(launcherDrawables,
                        sys("com.hkmc.hdradio/.Main", "com.hkmc.hdradio", ".Main", "HD Radio Data")));
        check("Media picks hom_media_66", "hom_media_66",
                SystemIconResolver.bestMatchingName(launcherDrawables,
                        sys("@class/Media", "", "Media", "Media")));
        check("Hyundai Pay picks hom_carpay_66", "hom_carpay_66",
                SystemIconResolver.bestMatchingName(launcherDrawables,
                        sys("com.hmc.carpay/.CarPayActivity", "com.hmc.carpay", ".CarPayActivity", "Hyundai Pay")));
        check("Bluelink picks hom_bluelink_66", "hom_bluelink_66",
                SystemIconResolver.bestMatchingName(launcherDrawables,
                        sys("com.hkmc.telematics.bluelink/.Main", "com.hkmc.telematics.bluelink", ".Main", "Bluelink")));
        check("Kia Connect picks hom_uvo_66", "hom_uvo_66",
                SystemIconResolver.bestMatchingName(launcherDrawables,
                        sys("com.hkmc.telematics.uvo/.Main", "com.hkmc.telematics.uvo", ".Main", "Kia Connect")));
        check("Notifications picks hom_noti_66", "hom_noti_66",
                SystemIconResolver.bestMatchingName(launcherDrawables,
                        sys("com.ivi.notification/.NoticeActivity", "com.ivi.notification", ".NoticeActivity", "Notifications")));
        check("User's Manual picks hom_qrmanual_66", "hom_qrmanual_66",
                SystemIconResolver.bestMatchingName(launcherDrawables,
                        sys("com.hkmc.qrmanual/*", "com.hkmc.qrmanual", "*", "User's Manual")));
        check("Valet Mode picks hom_valet_66", "hom_valet_66",
                SystemIconResolver.bestMatchingName(launcherDrawables,
                        sys("com.hmc.valetmode/.ValetActivity", "com.hmc.valetmode", ".ValetActivity", "Valet Mode")));
        check("Quiet Mode picks hom_quiet_66", "hom_quiet_66",
                SystemIconResolver.bestMatchingName(launcherDrawables,
                        sys("com.hkmc.quietmode/.QuietActivity", "com.hkmc.quietmode", ".QuietActivity", "Quiet Mode")));
        check("Voice Memo picks hom_voicememo_66", "hom_voicememo_66",
                SystemIconResolver.bestMatchingName(launcherDrawables,
                        sys("com.hkmc.voicememo/.MemoActivity", "com.hkmc.voicememo", ".MemoActivity", "Voice Memo")));
        check("Passenger Talk picks hom_passenger_talk_66 (not passenger_view)", "hom_passenger_talk_66",
                SystemIconResolver.bestMatchingName(launcherDrawables,
                        sys("com.hkmc.passengertalk/.TalkActivity", "com.hkmc.passengertalk", ".TalkActivity", "Passenger Talk")));
        check("Setup picks hom_setting_66 (not ims_seat or sml or _disabled)", "hom_setting_66",
                SystemIconResolver.bestMatchingName(launcherDrawables,
                        sys("com.android.settings/com.android.settings.Settings", "com.android.settings", "com.android.settings.Settings", "Setup")));

        System.out.println("SystemIconResolverTest: " + checks + " checks passed");
    }
}
