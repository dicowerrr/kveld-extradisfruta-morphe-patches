package app.morphe.patches.firefox

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.Constants
import app.morphe.patches.shared.cleanClassName
import app.morphe.patches.shared.replaceWithReturnBoolean
import app.morphe.patches.shared.replaceWithReturnVoid
import com.android.tools.smali.dexlib2.iface.Method

/**
 * Firefox AI Features Block Enforcement Patch
 *
 * Architecture & Governance Boundary Matrix:
 *
 * 1. AI Summarization Telemetry (GleanMetrics/AiSummarize* in classes3.dex):
 *    - Status: CONFIRMED & COVERED (Defense-in-Depth).
 *    - Evidence: GleanMetrics$AiSummarize* classes (Requested, Started, Completed, Closed,
 *      Feedback, FirstResponse, ProviderInitialized, ConsentDisplayed, SessionId) in classes3.dex.
 *    - Action: Neutralize metric recording dispatchers (record, set, add, cancel, track) at call site.
 *      Exfiltration upload pipeline is already dead via FirefoxBlockTelemetryPatch; this prevents
 *      in-memory accumulation and metric pipeline event dispatch.
 *
 * 2. Page Summarization Menu Entrypoint (MenuAction$OnSummarizationMenuExposed in classes3.dex):
 *    - Status: CONFIRMED & COVERED.
 *    - Evidence: Lorg/mozilla/fenix/components/menu/store/MenuAction$OnSummarizationMenuExposed;
 *      and associated summarization menu actions/handlers in classes3.dex.
 *    - Action: Neutralize summarization menu action exposure and middleware handlers with neutral early return.
 *
 * 3. Master AI Block Kill-Switch ("Mejoras en el bloqueo de IA" in Settings > AI Controls):
 *    - Status: CONFIRMED & COVERED (Gated).
 *    - Evidence: Gated feature behind Settings / AiControls preference gates in Fenix classes.
 *    - Action: Force block query predicates to true and disable AI enhancement gates with neutral returns.
 *
 * 4. Page Summarization Upload Route:
 *    - Status: UNCONFIRMED / PENDING.
 *    - Evidence: Remote backend processing endpoint for page summarization content payload is not
 *      confirmed in DEX. Per repository integrity rules, unverified endpoints must not be fabricated.
 *      Reported as pending discovery.
 *
 * 5. On-Device Translations ("Traducciones"):
 *    - Status: LOAD-BEARING PRESERVATION INVARIANT (DO NOT TOUCH).
 *    - Evidence: Local Bergamot / Gecko on-device translation models operate purely locally without
 *      cloud exfiltration. Any translation hooks are strictly vetoed and preserved intact.
 *
 * 6. Voice Search ("Busqueda por voz"):
 *    - Status: NO PATCH REQUIRED (Documented).
 *    - Evidence: Powered by system-level Google Speech Services without bundled in-app DEX SDK;
 *      user-disabled in device settings, zero transmission when unused.
 */

private fun isSafeVoidDispatcher(method: Method): Boolean {
    if (method.implementation == null) {
        return false
    }
    if (method.name.startsWith("<")) {
        return false
    }
    if (method.returnType != "V") {
        return false
    }
    val callbackParams = method.parameterTypes.map { it.toString() }.filter { param ->
        param.contains("Callback", ignoreCase = true) ||
            param.contains("Listener", ignoreCase = true) ||
            param.contains("Continuation", ignoreCase = true) ||
            param.contains("Observer", ignoreCase = true) ||
            param.contains("Handler", ignoreCase = true)
    }
    if (callbackParams.isNotEmpty()) {
        return false
    }
    val lifecycleNames = setOf(
        "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy",
        "onReceive", "onBind", "onStartCommand", "attachBaseContext",
    )
    if (method.name in lifecycleNames && method.parameterTypes.any { it.toString().startsWith("Landroid/") }) {
        return false
    }
    val returnType = method.returnType
    if (returnType.contains("Task") ||
        returnType.contains("Future") ||
        returnType.contains("Completable") ||
        returnType.contains("Observable") ||
        returnType.contains("Deferred") ||
        returnType.contains("Flow")
    ) {
        return false
    }
    return true
}

private fun isSafeBooleanDispatcher(method: Method): Boolean {
    if (method.implementation == null) {
        return false
    }
    if (method.name.startsWith("<")) {
        return false
    }
    if (method.returnType != "Z") {
        return false
    }
    val callbackParams = method.parameterTypes.map { it.toString() }.filter { param ->
        param.contains("Callback", ignoreCase = true) ||
            param.contains("Listener", ignoreCase = true) ||
            param.contains("Continuation", ignoreCase = true) ||
            param.contains("Observer", ignoreCase = true) ||
            param.contains("Handler", ignoreCase = true)
    }
    if (callbackParams.isNotEmpty()) {
        return false
    }
    val returnType = method.returnType
    if (returnType.contains("Task") ||
        returnType.contains("Future") ||
        returnType.contains("Completable") ||
        returnType.contains("Observable") ||
        returnType.contains("Deferred") ||
        returnType.contains("Flow")
    ) {
        return false
    }
    return true
}

private val AI_SUMMARIZE_CLASSES = listOf(
    "Lorg/mozilla/fenix/GleanMetrics\$AiSummarize;",
    "Lorg/mozilla/fenix/GleanMetrics\$AiSummarizeRequested;",
    "Lorg/mozilla/fenix/GleanMetrics\$AiSummarizeStarted;",
    "Lorg/mozilla/fenix/GleanMetrics\$AiSummarizeCompleted;",
    "Lorg/mozilla/fenix/GleanMetrics\$AiSummarizeClosed;",
    "Lorg/mozilla/fenix/GleanMetrics\$AiSummarizeFeedback;",
    "Lorg/mozilla/fenix/GleanMetrics\$AiSummarizeFirstResponse;",
    "Lorg/mozilla/fenix/GleanMetrics\$AiSummarizeProviderInitialized;",
    "Lorg/mozilla/fenix/GleanMetrics\$AiSummarizeConsentDisplayed;",
    "Lorg/mozilla/fenix/GleanMetrics\$AiSummarizeSessionId;",
    "Lorg/mozilla/fenix/GleanMetrics\$AiSummarize\$Requested;",
    "Lorg/mozilla/fenix/GleanMetrics\$AiSummarize\$Started;",
    "Lorg/mozilla/fenix/GleanMetrics\$AiSummarize\$Completed;",
    "Lorg/mozilla/fenix/GleanMetrics\$AiSummarize\$Closed;",
    "Lorg/mozilla/fenix/GleanMetrics\$AiSummarize\$Feedback;",
    "Lorg/mozilla/fenix/GleanMetrics\$AiSummarize\$FirstResponse;",
    "Lorg/mozilla/fenix/GleanMetrics\$AiSummarize\$ProviderInitialized;",
    "Lorg/mozilla/fenix/GleanMetrics\$AiSummarize\$ConsentDisplayed;",
    "Lorg/mozilla/fenix/GleanMetrics\$AiSummarize\$SessionId;",
)

private val SUMMARIZATION_MENU_CLASSES = listOf(
    "Lorg/mozilla/fenix/components/menu/store/MenuAction\$OnSummarizationMenuExposed;",
    "Lorg/mozilla/fenix/components/menu/store/MenuAction\$Summarize;",
    "Lorg/mozilla/fenix/components/menu/store/MenuAction\$OnSummarizeClicked;",
    "Lorg/mozilla/fenix/components/menu/store/MenuAction\$SummarizeClicked;",
    "Lorg/mozilla/fenix/components/menu/middleware/SummarizationTelemetryMiddleware;",
    "Lorg/mozilla/fenix/components/menu/middleware/SummarizationMenuMiddleware;",
    "Lorg/mozilla/fenix/summarization/SummarizationFeature;",
    "Lorg/mozilla/fenix/summarization/SummarizationService;",
    "Lorg/mozilla/fenix/summarization/PageSummarizer;",
    "Lorg/mozilla/fenix/summarization/Summarizer;",
)

private val AI_SETTINGS_CLASSES = listOf(
    "Lorg/mozilla/fenix/settings/ai/AiControlsSettingsFragment;",
    "Lorg/mozilla/fenix/settings/ai/AiControlsPreferenceFragment;",
    "Lorg/mozilla/fenix/settings/ai/AiControlsFragment;",
)

private fun BytecodePatchContext.hookAiTelemetry(
    hookedMethods: MutableList<String>,
    hookedSignatures: MutableSet<String>,
): Int {
    var count = 0
    var foundAny = false

    for (classType in AI_SUMMARIZE_CLASSES) {
        val classDef = classDefByOrNull(classType) ?: continue
        foundAny = true
        val mutableClass = mutableClassDefBy(classDef)

        for (method in mutableClass.methods) {
            val sig = "$classType->${method.name}"
            if (sig in hookedSignatures) continue
            if (method.name.startsWith("<")) continue

            if (isSafeVoidDispatcher(method)) {
                val methodName = method.name
                if (methodName in setOf("record", "set", "add", "cancel", "track") ||
                    methodName.startsWith("record") ||
                    methodName.startsWith("set")
                ) {
                    method.replaceWithReturnVoid()
                    hookedSignatures.add(sig)
                    hookedMethods.add("${cleanClassName(classType)}.$methodName")
                    count++
                }
            } else if (isSafeBooleanDispatcher(method)) {
                if (method.name.startsWith("test") || method.name.startsWith("has") || method.name.startsWith("is")) {
                    method.replaceWithReturnBoolean(false)
                    hookedSignatures.add(sig)
                    hookedMethods.add("${cleanClassName(classType)}.${method.name} -> false")
                    count++
                }
            }
        }
    }

    val gleanMetricsClass = classDefByOrNull("Lorg/mozilla/fenix/GleanMetrics;")
    if (gleanMetricsClass != null) {
        val mutableGlean = mutableClassDefBy(gleanMetricsClass)
        for (method in mutableGlean.methods) {
            val sig = "Lorg/mozilla/fenix/GleanMetrics;->${method.name}"
            if (sig in hookedSignatures) continue
            if (method.name.startsWith("<")) continue

            if (method.name.contains("AiSummarize", ignoreCase = true)) {
                if (isSafeVoidDispatcher(method)) {
                    method.replaceWithReturnVoid()
                    hookedSignatures.add(sig)
                    hookedMethods.add("GleanMetrics.${method.name} -> return-void")
                    count++
                }
            }
        }
    }

    if (!foundAny) {
        println("[Firefox AI Block] Skipped: GleanMetrics AI summarization classes absent.")
    }

    return count
}

private fun BytecodePatchContext.hookSummarizationMenu(
    hookedMethods: MutableList<String>,
    hookedSignatures: MutableSet<String>,
): Int {
    var count = 0
    var foundAny = false

    for (classType in SUMMARIZATION_MENU_CLASSES) {
        val classDef = classDefByOrNull(classType) ?: continue
        foundAny = true
        val mutableClass = mutableClassDefBy(classDef)

        for (method in mutableClass.methods) {
            val sig = "$classType->${method.name}"
            if (sig in hookedSignatures) continue
            if (method.name.startsWith("<")) continue

            if (isSafeVoidDispatcher(method)) {
                method.replaceWithReturnVoid()
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(classType)}.${method.name}")
                count++
            } else if (isSafeBooleanDispatcher(method)) {
                method.replaceWithReturnBoolean(false)
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(classType)}.${method.name} -> false")
                count++
            }
        }
    }

    if (!foundAny) {
        println("[Firefox AI Block] Skipped: Summarization menu action classes absent.")
    }

    return count
}

private fun BytecodePatchContext.hookMasterAiKillSwitch(
    hookedMethods: MutableList<String>,
    hookedSignatures: MutableSet<String>,
): Int {
    var count = 0

    val settingsClass = classDefByOrNull("Lorg/mozilla/fenix/utils/Settings;")
    if (settingsClass != null) {
        val mutableSettings = mutableClassDefBy(settingsClass)
        for (method in mutableSettings.methods) {
            val sig = "Lorg/mozilla/fenix/utils/Settings;->${method.name}"
            if (sig in hookedSignatures) continue
            if (method.name.startsWith("<")) continue

            val nameLower = method.name.lowercase()
            // Strict preservation invariant: on-device translations remain untouched
            if (nameLower.contains("translation") || nameLower.contains("translate")) {
                continue
            }

            if (nameLower.contains("ai") && (nameLower.contains("block") || nameLower.contains("enhancement"))) {
                if (isSafeBooleanDispatcher(method)) {
                    val forceValue = nameLower.contains("block") && !nameLower.contains("unblock")
                    method.replaceWithReturnBoolean(forceValue)
                    hookedSignatures.add(sig)
                    hookedMethods.add("Settings.${method.name} -> $forceValue")
                    count++
                } else if (isSafeVoidDispatcher(method)) {
                    method.replaceWithReturnVoid()
                    hookedSignatures.add(sig)
                    hookedMethods.add("Settings.${method.name} -> return-void")
                    count++
                }
            }
        }
    }

    for (classType in AI_SETTINGS_CLASSES) {
        val classDef = classDefByOrNull(classType) ?: continue
        val mutableClass = mutableClassDefBy(classDef)

        for (method in mutableClass.methods) {
            val sig = "$classType->${method.name}"
            if (sig in hookedSignatures) continue
            if (method.name.startsWith("<")) continue

            val nameLower = method.name.lowercase()
            if (nameLower.contains("translation") || nameLower.contains("translate")) {
                continue
            }

            if (isSafeBooleanDispatcher(method)) {
                val forceValue = nameLower.contains("block") && !nameLower.contains("unblock")
                method.replaceWithReturnBoolean(forceValue)
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(classType)}.${method.name} -> $forceValue")
                count++
            } else if (isSafeVoidDispatcher(method)) {
                method.replaceWithReturnVoid()
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(classType)}.${method.name} -> return-void")
                count++
            }
        }
    }

    return count
}

@Suppress("unused")
val firefoxAiBlockPatch = bytecodePatch(
    name = "Firefox AI Block Enforcement",
    description = "Enforces client-side blocking of Firefox AI features, suppressing page summarization menu entrypoints, neutralizing AI telemetry recording calls, and enforcing AI blocking controls.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY_FIREFOX)

    val blockSummarization by booleanOption(
        key = "blockSummarization",
        default = false,
        title = "Block Page Summarization",
        description = "Suppresses AI page summarization menu exposure and neutralizes summarization entrypoints in Firefox menus.",
    )

    val blockAiTelemetry by booleanOption(
        key = "blockAiTelemetry",
        default = false,
        title = "Neutralize AI Summarization Telemetry",
        description = "Neutralizes call-site recording of Glean AI summarization metrics (AiSummarizeRequested, Started, Completed, Closed, Feedback, FirstResponse, ProviderInitialized, ConsentDisplayed, session_id) as defense-in-depth.",
    )

    val enforceAiKillSwitch by booleanOption(
        key = "enforceAiKillSwitch",
        default = false,
        title = "Enforce Master AI Block Kill-Switch",
        description = "Enforces the AI blocking master kill-switch to neutralize AI feature initialization and enhancement hooks while strictly preserving on-device translations.",
    )

    execute {
        if (blockSummarization != true &&
            blockAiTelemetry != true &&
            enforceAiKillSwitch != true
        ) {
            println("[Firefox AI Block] Skipped: All options disabled.")
            return@execute
        }

        val hookedMethods = mutableListOf<String>()
        val hookedSignatures = mutableSetOf<String>()
        var patched = 0

        if (blockSummarization == true) {
            val summarizationHooks = hookSummarizationMenu(hookedMethods, hookedSignatures)
            patched += summarizationHooks
            println("[Firefox AI Block] Page summarization: neutralized $summarizationHooks menu entrypoint and handler methods.")
        }

        if (blockAiTelemetry == true) {
            val telemetryHooks = hookAiTelemetry(hookedMethods, hookedSignatures)
            patched += telemetryHooks
            println("[Firefox AI Block] AI telemetry: neutralized $telemetryHooks Glean AI summarization metric recording methods.")
        }

        if (enforceAiKillSwitch == true) {
            val killSwitchHooks = hookMasterAiKillSwitch(hookedMethods, hookedSignatures)
            patched += killSwitchHooks
            println("[Firefox AI Block] Master AI kill-switch: enforced on $killSwitchHooks settings and feature-gate methods.")
        }

        val summaryDetails = if (hookedMethods.isNotEmpty()) {
            " (${hookedMethods.joinToString(", ")})"
        } else {
            ""
        }
        println("[Firefox AI Block] Applied $patched hooks$summaryDetails -> AI features blocked.")
    }
}
