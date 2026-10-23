package app.morphe.patches.firefox

import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.Constants
import app.morphe.patches.shared.LocaleUtils
import app.morphe.patches.shared.disableComponentsWhere
import org.w3c.dom.Element

/**
 * Firefox Debloat Patch
 *
 * Universal Coverage & Classification Boundary Matrix:
 *
 * 1. Locale Slimming (res/values-*, assets/locales - .pak files):
 *    - Classification: UNIVERSAL_OK / NOT_APPLICABLE
 *    - Evidence: locale-pak sweep reported 0 hits (Firefox uses Gecko internal localization or
 *      standard Android res/values-* tables, not Chromium .pak). Standard Android localization
 *      is covered by the canonical universal LocaleResourceSlimmerPatch.
 *
 * 2. Screen Density Trimming (res/drawable-*dpi, res/mipmap-*dpi):
 *    - Classification: UNIVERSAL_OK
 *    - Evidence: Standard density qualifier directories are fully handled by the canonical
 *      universal DpiResourceSlimmerPatch.
 *
 * 3. Compiler & Root Build Junk Cleaner (META-INF/, APK root):
 *    - Classification: UNIVERSAL_OK
 *    - Evidence: 133/133 junk files (properties, versions, DebugProbesKt.bin, duplicate licenses)
 *      are pruned by the canonical universal CleanAppPackagingPatch.
 *
 * 4. Standard Background Sync Purge (RECEIVE_BOOT_COMPLETED, SystemJobService):
 *    - Classification: UNIVERSAL_OK
 *    - Evidence: Standard AndroidX WorkManager services (SystemJobService) and boot receivers
 *      (RECEIVE_BOOT_COMPLETED) are purged by the canonical universal BackgroundSyncPurgePatch.
 *
 * 5. Native Companion Library Trimming (lib/<abi> - .so files):
 *    - Classification: NOT_APPLICABLE (Load-Bearing Veto)
 *    - Evidence: 18 .so files per ABI (libxul.so, libmozglue.so, libmozavcodec.so, libmozavutil.so,
 *      libnss3.so, libfreebl3.so, libsoftokn3.so, liblgpllibs.so, libgkcodecs.so, libplugin-container.so,
 *      libsigner.so, libclearkey.so, libcrashhelper.so, libcrashtools.so, libdatastore_shared_counter.so,
 *      libandroidx.graphics.path.so, libjnidispatch.so). libxul and libmozglue are core Gecko engine
 *      binaries (never touch). All companion libraries carry >=1 loadLibrary call sites across classes*.dex
 *      or are transitively dead via telemetry manifest purge. Zero unreferenced companion .so proven.
 *      Per app-debloat Section 1.A, >=1 reference strictly vetoes zeroing. Zeroing 0 files.
 *
 * 6. ML Models (.tflite, .onnx, etc.):
 *    - Classification: NOT_APPLICABLE
 *    - Evidence: ml-model sweep reported 0 hits.
 *
 * 7. Large Heap Removal (android:largeHeap):
 *    - Classification: NOT_APPLICABLE
 *    - Evidence: large-heap sweep reported 0 hits (android:largeHeap attribute absent from <application>).
 *
 * 8. Built-in WebExtension Asset Bundles (assets/extensions/ bundles):
 *    - Classification: DEDICATED_NEEDED
 *    - Universal Failure Evidence: The universal junk cleaner (CleanAppPackagingPatch) strictly protects
 *      the 'assets/' directory and has no domain knowledge of browser extension directory trees
 *      (assets/extensions/ subdirectories). Sweep detected 19/19 heavy assets matching assets/extensions/ bundles (manifest.json).
 *      Pruning built-in WebExtensions requires a dedicated patch axis.
 *
 * 9. Gecko Profiler Service (org.mozilla.gecko.process.GeckoChildProcessServices$profiler):
 *    - Classification: DEDICATED_NEEDED
 *    - Universal Failure Evidence: BackgroundSyncPurgePatch only matches standard androidx.work.*
 *      services and standard lifecycle receivers; it cannot target Mozilla Gecko-specific child
 *      process profiler services or Firefox performance diagnostic foreground services.
 *      Sweep detected profiler foreground service in manifest. Neutralization requires a dedicated axis.
 *
 * 10. Onboarding & Tutorial Media (res/raw - .mp4 files):
 *     - Classification: NOT_APPLICABLE (Defensive stubbing retained)
 *     - Evidence: heavy-asset sweep reported 19/19 hits which were all WebExtension asset bundles.
 *       Zero MP4 tutorial videos were detected in res/raw/. Defensive stubbing is retained under
 *       an opt-in toggle to protect future builds.
 */

private val EMPTY_MP4_HEADER by lazy {
    byteArrayOf(
        0x00, 0x00, 0x00, 0x20, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D,
        0x00, 0x00, 0x02, 0x00, 0x69, 0x73, 0x6F, 0x6D, 0x69, 0x73, 0x6F, 0x32,
        0x61, 0x76, 0x63, 0x31, 0x6D, 0x70, 0x34, 0x31,
    )
}

@Suppress("unused")
val firefoxDebloatPatch = resourcePatch(
    name = "Firefox Debloat",
    description = "Removes built-in WebExtension asset bundles, disables the Gecko profiler foreground service, and stubs unused onboarding media to reduce APK footprint and background overhead.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY_FIREFOX)

    val pruneExtensionBundles by booleanOption(
        key = "pruneExtensionBundles",
        default = false,
        title = "Prune Built-in WebExtension Bundles",
        description = "Removes built-in WebExtension asset bundles from assets/extensions/ to reduce APK size.",
    )

    val disableProfilerService by booleanOption(
        key = "disableProfilerService",
        default = false,
        title = "Disable Gecko Profiler Service",
        description = "Disables Mozilla Gecko profiler foreground services in AndroidManifest.xml.",
    )

    val stubOnboardingMedia by booleanOption(
        key = "stubOnboardingMedia",
        default = false,
        title = "Stub Onboarding Media",
        description = "Stubs tutorial and onboarding video files in res/raw/ if present.",
    )

    execute {
        if (!(pruneExtensionBundles ?: false) &&
            !(disableProfilerService ?: false) &&
            !(stubOnboardingMedia ?: false)
        ) {
            println("[Firefox Debloat] All debloat options disabled - skipping.")
            return@execute
        }

        var prunedExtensionFiles = 0
        var savedExtensionBytes = 0L
        var disabledProfilerCount = 0
        var stubbedMediaCount = 0
        var savedMediaBytes = 0L

        // 1. Prune built-in WebExtension asset bundles in assets/extensions/
        if (pruneExtensionBundles ?: false) {
            val extensionsDir = get("assets/extensions")
            if (extensionsDir.exists() && extensionsDir.isDirectory) {
                extensionsDir.walkBottomUp().forEach { file ->
                    if (file.isFile) {
                        val size = file.length()
                        if (file.delete()) {
                            prunedExtensionFiles++
                            savedExtensionBytes += size
                        }
                    } else if (file.isDirectory) {
                        file.delete()
                    }
                }
            }
        }

        // 2. Disable Mozilla Gecko profiler foreground service in AndroidManifest.xml
        if (disableProfilerService ?: false) {
            val manifestFile = get("AndroidManifest.xml")
            if (manifestFile.exists()) {
                document(manifestFile.absolutePath).use { doc ->
                    val application = doc.documentElement.getElementsByTagName("application").item(0) as? Element
                    if (application != null) {
                        val profilerServiceNames = setOf(
                            "org.mozilla.gecko.process.GeckoChildProcessServices\$profiler",
                            "org.mozilla.fenix.perf.ProfilerService",
                        )
                        disabledProfilerCount = application.disableComponentsWhere("service") { name ->
                            name in profilerServiceNames || name.contains("profiler", ignoreCase = true)
                        }
                    }
                }
            }
        }

        // 3. Stub onboarding / tutorial MP4 videos in res/raw/ (defensive axis)
        if (stubOnboardingMedia ?: false) {
            decode("res/raw*")
            val resRawDir = get("res/raw")
            if (resRawDir.exists() && resRawDir.isDirectory) {
                resRawDir.walkTopDown().filter { it.isFile && it.name.endsWith(".mp4", ignoreCase = true) }.forEach { file ->
                    val originalSize = file.length()
                    if (originalSize > EMPTY_MP4_HEADER.size) {
                        file.writeBytes(EMPTY_MP4_HEADER)
                        val saved = originalSize - file.length()
                        if (saved > 0) {
                            stubbedMediaCount++
                            savedMediaBytes += saved
                        }
                    }
                }
            }
        }

        val totalSavedBytes = savedExtensionBytes + savedMediaBytes
        val savedFormatted = LocaleUtils.formatBytes(totalSavedBytes)
        println(
            "[Firefox Debloat] Pruned $prunedExtensionFiles extension assets, " +
                "disabled $disabledProfilerCount profiler services, " +
                "stubbed $stubbedMediaCount media files -> Saved $savedFormatted",
        )
    }
}
