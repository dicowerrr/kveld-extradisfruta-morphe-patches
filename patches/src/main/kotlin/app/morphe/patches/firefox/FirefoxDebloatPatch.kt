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
 *    - Classification: DEDICATED_NEEDED (libcrashhelper.so) & NOT_APPLICABLE (All others)
 *    - Evidence: Stock Firefox bundles 18 .so binaries per ABI vs 15 in Fennec F-Droid 158.0.2.
 *      Audit of candidate binaries:
 *      a) libcrashhelper.so: Companion for the standalone CrashHelper service. Safely zeroed in-situ
 *         when paired with disabling org.mozilla.gecko.crashhelper.CrashHelper in AndroidManifest.xml.
 *      b) libsigner.so: Loaded synchronously during early startup by the closed-source Adjust SDK
 *         (com.adjust.sdk.sig.Signer). In stock Firefox, Adjust bytecode remains in classes*.dex
 *         (unlike Fennec where Adjust source is removed). Zeroing libsigner.so causes UnsatisfiedLinkError
 *         in the main process. Excluded from zeroing.
 *      c) libcrashtools.so: Crashdump unwinding utility potentially referenced by CrashHandlerService
 *         which GeckoRuntime.init requires at startup. Excluded from zeroing to guarantee startup safety.
 *      d) libxul.so & libmozglue.so: Core Gecko layout engine (strictly load-bearing veto, never touched).
 *      e) Remaining 13 .so: Core runtime dependencies of Gecko (mozavcodec, mozavutil, nss3, freebl3,
 *         softokn3, lgpllibs, gkcodecs, plugin-container, clearkey, datastore_shared_counter,
 *         androidx.graphics.path, jnidispatch).
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
 * 9. Gecko Profiler & CrashHelper Services:
 *    - Classification: DEDICATED_NEEDED
 *    - Universal Failure Evidence: BackgroundSyncPurgePatch only matches standard androidx.work.*
 *      services and standard lifecycle receivers; it cannot target Mozilla Gecko-specific child
 *      process profiler services or the standalone CrashHelper service.
 *      Disabling org.mozilla.gecko.crashhelper.CrashHelper prevents spawning the :crashhelper_disable_art_image_
 *      process and prevents UnsatisfiedLinkError on zeroed libcrashhelper.so.
 *      CrashHandlerService is strictly preserved as required by GeckoRuntime.init.
 *
 * 10. Onboarding & Tutorial Media (res/raw - .mp4 files):
 *     - Classification: NOT_APPLICABLE (Defensive stubbing retained)
 *     - Evidence: heavy-asset sweep reported 19/19 hits which were all WebExtension asset bundles.
 *       Zero MP4 tutorial videos were detected in res/raw/. Defensive stubbing is retained under
 *       an opt-in toggle to protect future builds.
 */

private val EMPTY_STUB_BYTES = byteArrayOf()

private val TELEMETRY_NATIVE_LIBS = listOf(
    "libcrashhelper.so",
)

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
    description = "Removes built-in WebExtension asset bundles, zeroes unneeded crash helper native library, disables the Gecko profiler and standalone CrashHelper services, and stubs unused onboarding media to reduce APK footprint and background overhead.",
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

    val stripTelemetryNativeLibs by booleanOption(
        key = "stripTelemetryNativeLibs",
        default = false,
        title = "Strip Crash Helper Native Library",
        description = "Zeroes unneeded libcrashhelper.so binary and disables the standalone CrashHelper service.",
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
            !(stripTelemetryNativeLibs ?: false) &&
            !(stubOnboardingMedia ?: false)
        ) {
            println("[Firefox Debloat] All debloat options disabled - skipping.")
            return@execute
        }

        var prunedExtensionFiles = 0
        var savedExtensionBytes = 0L
        var disabledServicesCount = 0
        var zeroedNativeLibsCount = 0
        var savedNativeBytes = 0L
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

        // 2. In-situ zeroing of crash helper native binary
        if (stripTelemetryNativeLibs ?: false) {
            val candidateAbis = listOf("lib/arm64-v8a", "lib/armeabi-v7a", "lib/x86_64", "lib/x86")
            candidateAbis.forEach { abi ->
                TELEMETRY_NATIVE_LIBS.forEach { libName ->
                    // Invariant: Never touch core Gecko engine libraries
                    if (libName == "libxul.so" || libName == "libmozglue.so") return@forEach

                    val libFile = get("$abi/$libName")
                    if (libFile.exists() && libFile.isFile) {
                        val originalSize = libFile.length()
                        if (originalSize > 0) {
                            libFile.writeBytes(EMPTY_STUB_BYTES)
                            savedNativeBytes += originalSize
                            zeroedNativeLibsCount++
                        }
                    }
                }
            }
        }

        // 3. Disable profiler and standalone CrashHelper services in AndroidManifest.xml
        val servicesToDisable = mutableSetOf<String>()
        if (disableProfilerService ?: false) {
            servicesToDisable.add("org.mozilla.gecko.process.GeckoChildProcessServices\$profiler")
            servicesToDisable.add("org.mozilla.fenix.perf.ProfilerService")
        }
        if ((stripTelemetryNativeLibs ?: false) || (disableProfilerService ?: false)) {
            // Disables standalone CrashHelper service to prevent UnsatisfiedLinkError on zeroed libcrashhelper.so.
            // Preserves CrashHandlerService intact, as required by GeckoRuntime.init.
            servicesToDisable.add("org.mozilla.gecko.crashhelper.CrashHelper")
        }

        if (servicesToDisable.isNotEmpty()) {
            val manifestFile = get("AndroidManifest.xml")
            if (manifestFile.exists()) {
                document(manifestFile.absolutePath).use { doc ->
                    val application = doc.documentElement.getElementsByTagName("application").item(0) as? Element
                    if (application != null) {
                        disabledServicesCount = application.disableComponentsWhere("service") { name ->
                            name in servicesToDisable ||
                                ((disableProfilerService ?: false) && name.contains("profiler", ignoreCase = true))
                        }
                    }
                }
            }
        }

        // 4. Stub onboarding / tutorial MP4 videos in res/raw/ (defensive axis)
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

        val totalSavedBytes = savedExtensionBytes + savedNativeBytes + savedMediaBytes
        val savedFormatted = LocaleUtils.formatBytes(totalSavedBytes)
        println(
            "[Firefox Debloat] Pruned $prunedExtensionFiles extension assets, " +
                "zeroed $zeroedNativeLibsCount telemetry native libs, " +
                "disabled $disabledServicesCount background services, " +
                "stubbed $stubbedMediaCount media files -> Saved $savedFormatted",
        )
    }
}
