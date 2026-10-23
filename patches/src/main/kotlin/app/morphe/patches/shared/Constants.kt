package app.morphe.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

/**
 * Single source of truth for target apps: package names, the one supported
 * version per app, and the [app.morphe.patcher.patch.Compatibility] contracts
 * consumed by patches through `compatibleWith(...)`.
 *
 * Template for a new target:
 *
 * ```
 * const val EXAMPLE_PACKAGE_NAME = "com.example.app"
 * const val EXAMPLE_TARGET_VERSION = "1.0.0"
 * const val EXAMPLE_INPUT_SHA256 = ""
 *
 * val COMPATIBILITY_EXAMPLE = Compatibility(
 *     name = "Example",
 *     packageName = EXAMPLE_PACKAGE_NAME,
 *     apkFileType = ApkFileType.APK,
 *     appIconColor = 0x000000,
 *     targets = listOf(
 *         AppTarget(
 *             version = EXAMPLE_TARGET_VERSION,
 *             description = "Download com.example.app v$EXAMPLE_TARGET_VERSION (APK) from APKMirror",
 *         )
 *     )
 * )
 * ```
 */
object Constants {
    const val INSTAGRAM_PACKAGE_NAME = "com.instagram.android"
    const val INSTAGRAM_TARGET_VERSION = "447.0.0.55.81"
    const val INSTAGRAM_INPUT_SHA256 = "b3075e2bb88b14ced55399b825b884b5d90c9945096833fbb0e4a2befd16ebad"

    val COMPATIBILITY_INSTAGRAM = Compatibility(
        name = "Instagram",
        packageName = INSTAGRAM_PACKAGE_NAME,
        apkFileType = ApkFileType.APKM,
        appIconColor = 0xE1306C,
        targets = listOf(
            AppTarget(
                version = INSTAGRAM_TARGET_VERSION,
                description = "Download com.instagram.android v$INSTAGRAM_TARGET_VERSION (APKM) arm64-v8a 120-480dpi versionCode 385311891 (BUNDLE3 S) from APKMirror",
            )
        )
    )

    const val TWITTER_PACKAGE_NAME = "com.twitter.android"
    const val TWITTER_TARGET_VERSION = "12.36.1-prod.01"
    const val TWITTER_INPUT_SHA256 = "0bef573e442f4aa7dd6111467859b97644d6e3bd2c8d6aadff3cffc175e1d6d2"

    val COMPATIBILITY_TWITTER = Compatibility(
        name = "X",
        packageName = TWITTER_PACKAGE_NAME,
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x000000,
        targets = listOf(
            AppTarget(
                version = TWITTER_TARGET_VERSION,
                description = "Download com.twitter.android v$TWITTER_TARGET_VERSION (APKM) arm64-v8a 120-640dpi versionCode 312361001 (BUNDLE 35 S) from APKMirror",
            )
        )
    )

    const val MOOVIT_PACKAGE_NAME = "com.tranzmate"
    const val MOOVIT_TARGET_VERSION = "5.201.1.1809"
    const val MOOVIT_INPUT_SHA256 = "135e7a1964dd71395e6e2f00625d555d9efddb6c1d0b320dffd6f0bb12e4863d"

    val COMPATIBILITY_MOOVIT = Compatibility(
        name = "Moovit",
        packageName = MOOVIT_PACKAGE_NAME,
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x1864E4,
        targets = listOf(
            AppTarget(
                version = MOOVIT_TARGET_VERSION,
                description = "Download com.tranzmate v$MOOVIT_TARGET_VERSION (APKM) arm64-v8a 160-640dpi versionCode 1809 (BUNDLE 31 S) from APKMirror",
            )
        )
    )

    const val POWERPOINT_PACKAGE_NAME = "com.microsoft.office.powerpoint"
    const val POWERPOINT_TARGET_VERSION = "16.0.20527.20034"
    const val POWERPOINT_INPUT_SHA256 = "e9379a409f0cc72ee0815c23a50852248384e0c65b2f83bcdc8748760456b937"

    val COMPATIBILITY_POWERPOINT = Compatibility(
        name = "PowerPoint",
        packageName = POWERPOINT_PACKAGE_NAME,
        apkFileType = ApkFileType.APKM,
        appIconColor = 0xE44C50,
        targets = listOf(
            AppTarget(
                version = POWERPOINT_TARGET_VERSION,
                description = "Download com.microsoft.office.powerpoint v$POWERPOINT_TARGET_VERSION (APKM) arm64-v8a 120-480dpi versionCode 2005437911 (BUNDLE 5 S) from APKMirror",
            )
        )
    )

    const val CAPCUT_PACKAGE_NAME = "com.lemon.lvoverseas"
    const val CAPCUT_TARGET_VERSION = "19.9.0"
    const val CAPCUT_INPUT_SHA256 = "3c74a9941237451953fe4ed1abe477b7413d11d11a4b5b45a17441afd30f2c38"

    val COMPATIBILITY_CAPCUT = Compatibility(
        name = "CapCut",
        packageName = CAPCUT_PACKAGE_NAME,
        apkFileType = ApkFileType.APK,
        appIconColor = 0x000000,
        targets = listOf(
            AppTarget(
                version = CAPCUT_TARGET_VERSION,
                description = "Download com.lemon.lvoverseas v$CAPCUT_TARGET_VERSION (APK) arm64-v8a nodpi versionCode 19900002 from APKMirror",
            )
        )
    )

    const val OFFICEHUB_PACKAGE_NAME = "com.microsoft.office.officehubrow"
    const val OFFICEHUB_TARGET_VERSION = "16.0.20604.20012"
    const val OFFICEHUB_INPUT_SHA256 = "09b0ba87a2d8e630c5bc1b807b3f9cb7db3135f2e8d582fe51fdf923bf3c1201"

    val COMPATIBILITY_OFFICEHUB = Compatibility(
        name = "Copilot",
        packageName = OFFICEHUB_PACKAGE_NAME,
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x109CF0,
        targets = listOf(
            AppTarget(
                version = OFFICEHUB_TARGET_VERSION,
                description = "Download $OFFICEHUB_PACKAGE_NAME v$OFFICEHUB_TARGET_VERSION (APKM) arm64-v8a 120-480dpi versionCode 45489613 (BUNDLE 32 S) from APKMirror",
            )
        )
    )

    const val HEVY_PACKAGE_NAME = "com.hevy"
    const val HEVY_TARGET_VERSION = "3.2.2"
    const val HEVY_INPUT_SHA256 = ""

    val COMPATIBILITY_HEVY = Compatibility(
        name = "Hevy - Gym Log Workout Tracker",
        packageName = HEVY_PACKAGE_NAME,
        apkFileType = ApkFileType.APKM,
        appIconColor = 0xFF4500,
        targets = listOf(
            AppTarget(
                version = HEVY_TARGET_VERSION,
                description = "Download com.hevy v$HEVY_TARGET_VERSION (APKM) arm64-v8a 120-640dpi versionCode 3308730 (BUNDLE) from APKMirror",
            )
        )
    )

    const val NOKOPRINT_PACKAGE_NAME = "com.nokoprint"
    const val NOKOPRINT_TARGET_VERSION = "5.28.6"
    const val NOKOPRINT_INPUT_SHA256 = ""

    val COMPATIBILITY_NOKOPRINT = Compatibility(
        name = "NokoPrint - WiFi, Bluetooth, USB",
        packageName = NOKOPRINT_PACKAGE_NAME,
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x0288D1,
        targets = listOf(
            AppTarget(
                version = NOKOPRINT_TARGET_VERSION,
                description = "Download com.nokoprint v$NOKOPRINT_TARGET_VERSION (XAPK bundle) from APKPure",
            )
        )
    )

    const val XIAOMI_EARBUDS_PACKAGE_NAME = "com.mi.earphone"
    const val XIAOMI_EARBUDS_TARGET_VERSION = "1.38.0i"
    const val XIAOMI_EARBUDS_INPUT_SHA256 = ""

    val COMPATIBILITY_XIAOMI_EARBUDS = Compatibility(
        name = "Xiaomi Earbuds",
        packageName = XIAOMI_EARBUDS_PACKAGE_NAME,
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0xFF6700,
        targets = listOf(
            AppTarget(
                version = XIAOMI_EARBUDS_TARGET_VERSION,
                description = "Download com.mi.earphone v$XIAOMI_EARBUDS_TARGET_VERSION (XAPK bundle) from APKPure",
            )
        )
    )

    const val SUBE_PACKAGE_NAME = "com.sube.app"
    const val SUBE_TARGET_VERSION = "2.7.25"
    const val SUBE_INPUT_SHA256 = ""

    val COMPATIBILITY_SUBE = Compatibility(
        name = "SUBE",
        packageName = SUBE_PACKAGE_NAME,
        apkFileType = ApkFileType.XAPK,
        appIconColor = 0x00A4E4,
        targets = listOf(
            AppTarget(
                version = SUBE_TARGET_VERSION,
                description = "Download com.sube.app v$SUBE_TARGET_VERSION (XAPK bundle) from APKPure",
            )
        )
    )

    const val SDMSE_PACKAGE_NAME = "eu.darken.sdmse"
    const val SDMSE_TARGET_VERSION = "2.2.2-rc0"
    const val SDMSE_INPUT_SHA256 = ""

    val COMPATIBILITY_SDMSE = Compatibility(
        name = "SD Maid SE",
        packageName = SDMSE_PACKAGE_NAME,
        apkFileType = ApkFileType.APK,
        appIconColor = 0x4CAF50,
        targets = listOf(
            AppTarget(
                version = SDMSE_TARGET_VERSION,
                description = "Download eu.darken.sdmse v$SDMSE_TARGET_VERSION (APK) FOSS-RELEASE versionCode 20202000",
            )
        )
    )

    const val FIREFOX_PACKAGE_NAME = "org.mozilla.firefox"
    const val FIREFOX_TARGET_VERSION = "158.0.2"
    const val FIREFOX_INPUT_SHA256 = ""

    val COMPATIBILITY_FIREFOX = Compatibility(
        name = "Firefox",
        packageName = FIREFOX_PACKAGE_NAME,
        apkFileType = ApkFileType.APK,
        appIconColor = 0xFF7139,
        targets = listOf(
            AppTarget(
                version = FIREFOX_TARGET_VERSION,
                description = "Download org.mozilla.firefox v158.0.2 (APK) arm64-v8a nodpi versionCode 2016191575 from APKMirror",
            )
        )
    )
}
