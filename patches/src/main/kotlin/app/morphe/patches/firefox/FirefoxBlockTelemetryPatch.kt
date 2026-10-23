package app.morphe.patches.firefox

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.Constants
import app.morphe.patches.shared.cleanClassName
import app.morphe.patches.shared.clearTryBlocks
import app.morphe.patches.shared.disableComponentsWhere
import app.morphe.patches.shared.ensureRegisterCount
import app.morphe.patches.shared.removeComponentDiscoveryRegistrarsWhere
import app.morphe.patches.shared.replaceWithReturnBoolean
import app.morphe.patches.shared.setApplicationMetaData
import app.morphe.patches.shared.stripPermissionsWhere
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.w3c.dom.Element

private val blockedPermissions = setOf(
    "com.adjust.preinstall.READ_PERMISSION",
    "com.google.android.gms.permission.AD_ID",
    "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
    "android.permission.ACCESS_ADSERVICES_AD_ID",
    "android.permission.ACCESS_ADSERVICES_TOPICS",
    "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE",
)

private val blockedComponents = setOf(
    // Adjust Attribution
    "com.adjust.sdk.AdjustReferrerReceiver",

    // Google DataTransport
    "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver",
    "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService",
    "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery",

    // Firefox / Fenix Crash Report Upload Services (uploaders only)
    // Note: CrashHandlerService (mozilla.components.lib.crash.handler.CrashHandlerService / GeckoView)
    // is required to be registered as an enabled service by GeckoRuntime.init during startup
    // (throws IllegalArgumentException: Crash handler must be registered as a service).
    // Exfiltration suppression is enforced at consumer layer (CrashReporter stubs, Socorro host rewrite,
    // and disabled uploaders: SendCrashReportService / SendCrashTelemetryService / DataTransport), not in the manifest.
    "org.mozilla.fenix.crashreporting.SendCrashReportService",
    "org.mozilla.fenix.crashreporting.SendCrashTelemetryService",
    "mozilla.components.lib.crash.service.SendCrashReportService",
    "mozilla.components.lib.crash.service.SendCrashTelemetryService",
)

private val optOutMetadata = listOf(
    "google_analytics_adid_collection_enabled" to "false",
    "google_analytics_default_allow_ad_personalization_signals" to "false",
    "firebase_analytics_collection_enabled" to "false",
    "firebase_analytics_collection_deactivated" to "true",
    "firebase_crashlytics_collection_enabled" to "false",
    "firebase_performance_collection_enabled" to "false",
    "firebase_performance_collection_deactivated" to "true",
)

private val firefoxTelemetryResourcePatch = resourcePatch(
    name = "Firefox Telemetry Manifest Purge",
    description = "Strips advertising and attribution permissions, disables Adjust, crash reporting, and DataTransport components, and injects opt-out metadata in AndroidManifest.xml.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY_FIREFOX)

    execute {
        val manifestFile = get("AndroidManifest.xml")
        if (!manifestFile.exists()) {
            println("[Block Telemetry & Trackers] Skipped: AndroidManifest.xml not found.")
            return@execute
        }

        var removedPermissions = 0
        var disabledComponents = 0
        var removedRegistrars = 0
        var injectedMetadata = 0

        document(manifestFile.absolutePath).use { doc ->
            val root = doc.documentElement
            val application = root.getElementsByTagName("application").item(0) as? Element

            // 1. Strip tracking, ad, and preinstall attribution permissions
            removedPermissions = root.stripPermissionsWhere { it in blockedPermissions }.size

            if (application != null) {
                // 2. Disable components (exact blocked set + dynamic crash uploaders and adjust components)
                disabledComponents = application.disableComponentsWhere(
                    "activity", "provider", "service", "receiver"
                ) { name ->
                    name in blockedComponents ||
                        name.endsWith("SendCrashReportService") ||
                        name.endsWith("SendCrashTelemetryService") ||
                        (name.contains("adjust", ignoreCase = true) &&
                            (name.contains("receiver", ignoreCase = true) ||
                                name.contains("service", ignoreCase = true) ||
                                name.contains("provider", ignoreCase = true)))
                }

                // 3. Remove discovery meta-data registrars if any present (excluding MLKit and push messaging)
                removedRegistrars = application.removeComponentDiscoveryRegistrarsWhere { name ->
                    val matchesKeyword = listOf(
                        "analytics",
                        "measurement",
                        "crashlytics",
                        "perf",
                        "remoteconfig",
                        "sessions",
                    ).any { name.contains(it, ignoreCase = true) }
                    val isExcluded = name.contains("mlkit", ignoreCase = true) ||
                        name.contains("messaging", ignoreCase = true)
                    matchesKeyword && !isExcluded
                }

                // 4. Inject declarative SDK opt-out metadata flags
                optOutMetadata.forEach { (name, value) ->
                    application.setApplicationMetaData(name, value)
                    injectedMetadata++
                }
            }
        }

        println("[Block Telemetry & Trackers] Stripped $removedPermissions permissions, disabled $disabledComponents tracking components, removed $removedRegistrars discovery registrars, and injected $injectedMetadata opt-out flags in AndroidManifest.xml.")
    }
}

private fun isSafeVoidDispatcher(method: Method): Boolean {
    if (method.implementation == null) {
        return false
    }
    if (method.name == "<init>" || method.name == "<clinit>") {
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
        returnType.contains("Deferred")
    ) {
        return false
    }
    return true
}

private fun isSafeBooleanDispatcher(method: Method): Boolean {
    if (method.implementation == null) {
        return false
    }
    if (method.name == "<init>" || method.name == "<clinit>") {
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
    return true
}

private fun BytecodePatchContext.hookAdjustSdk(
    hookedMethods: MutableList<String>,
    hookedSignatures: MutableSet<String>,
) {
    val adjustClasses = listOf(
        "Lcom/adjust/sdk/PackageHandler;",
        "Lcom/adjust/sdk/Adjust;",
        "Lcom/adjust/sdk/ActivityHandler;",
    )

    var foundAny = false
    for (classType in adjustClasses) {
        val classDef = classDefByOrNull(classType) ?: continue
        foundAny = true
        val mutableClass = mutableClassDefBy(classDef)

        for (method in mutableClass.methods) {
            val sig = "$classType->${method.name}"
            if (sig in hookedSignatures) continue

            when (classType) {
                "Lcom/adjust/sdk/PackageHandler;" -> {
                    if (method.name in setOf("addPackage", "sendFirstPackage", "sendNextPackage") &&
                        isSafeVoidDispatcher(method)
                    ) {
                        method.addInstructions(0, "return-void")
                        hookedSignatures.add(sig)
                        hookedMethods.add("Adjust.PackageHandler.${method.name}")
                    }
                }
                "Lcom/adjust/sdk/Adjust;" -> {
                    if (method.name in setOf("onCreate", "trackEvent", "trackSubsessionStart", "trackSubsessionEnd") &&
                        isSafeVoidDispatcher(method)
                    ) {
                        method.addInstructions(0, "return-void")
                        hookedSignatures.add(sig)
                        hookedMethods.add("Adjust.${method.name}")
                    }
                }
                "Lcom/adjust/sdk/ActivityHandler;" -> {
                    if (method.name == "trackEvent" && isSafeVoidDispatcher(method)) {
                        method.addInstructions(0, "return-void")
                        hookedSignatures.add(sig)
                        hookedMethods.add("Adjust.ActivityHandler.trackEvent")
                    }
                }
            }
        }
    }

    if (!foundAny) {
        println("[Block Telemetry & Trackers] Skipped: Adjust SDK classes absent.")
    }
}

private fun BytecodePatchContext.hookGleanTelemetry(
    hookedMethods: MutableList<String>,
    hookedSignatures: MutableSet<String>,
) {
    val gleanClasses = listOf(
        "Lmozilla/telemetry/glean/Glean;",
        "Lmozilla/telemetry/glean/internal/GleanInternal;",
        "Lmozilla/components/service/glean/Glean;",
    )

    var foundAny = false
    for (classType in gleanClasses) {
        val classDef = classDefByOrNull(classType) ?: continue
        foundAny = true
        val mutableClass = mutableClassDefBy(classDef)

        for (method in mutableClass.methods) {
            val sig = "$classType->${method.name}"
            if (sig in hookedSignatures) continue

            if (method.name == "isUploadEnabled" && isSafeBooleanDispatcher(method)) {
                method.replaceWithReturnBoolean(false)
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(classType)}.isUploadEnabled")
            } else if (method.name in setOf("handleStartupPings", "submitPing") && isSafeVoidDispatcher(method)) {
                method.addInstructions(0, "return-void")
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(classType)}.${method.name}")
            }
        }
    }

    val pingTypeClasses = listOf(
        "Lmozilla/telemetry/glean/internal/PingType;",
        "Lmozilla/telemetry/glean/PingType;",
    )
    for (classType in pingTypeClasses) {
        val classDef = classDefByOrNull(classType) ?: continue
        foundAny = true
        val mutableClass = mutableClassDefBy(classDef)

        for (method in mutableClass.methods) {
            val sig = "$classType->${method.name}"
            if (sig in hookedSignatures) continue

            if (method.name == "submit" && isSafeVoidDispatcher(method)) {
                method.addInstructions(0, "return-void")
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(classType)}.submit")
            }
        }
    }

    val uploaderClasses = listOf(
        "Lmozilla/telemetry/glean/net/HttpUploader;",
        "Lmozilla/telemetry/glean/net/BaseUploader;",
        "Lmozilla/components/service/glean/net/ConceptFetchHttpUploader;",
        "Lmozilla/telemetry/glean/scheduler/PingUploadWorker;",
        "Lmozilla/telemetry/glean/scheduler/PingUploadWorker\$Companion;",
    )
    for (classType in uploaderClasses) {
        val classDef = classDefByOrNull(classType) ?: continue
        foundAny = true
        val mutableClass = mutableClassDefBy(classDef)

        for (method in mutableClass.methods) {
            val sig = "$classType->${method.name}"
            if (sig in hookedSignatures) continue

            val methodName = method.name
            if ((methodName in setOf("triggerUpload", "enqueueUpload") ||
                    methodName.startsWith("performUpload") ||
                    methodName.startsWith("enqueueWorker")) &&
                isSafeVoidDispatcher(method)
            ) {
                method.addInstructions(0, "return-void")
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(classType)}.$methodName")
            } else if (methodName == "doWork" &&
                method.returnType == "Landroidx/work/ListenableWorker\$Result;" &&
                method.implementation != null
            ) {
                method.clearTryBlocks()
                method.ensureRegisterCount(1)
                method.removeInstructions(0, method.implementation!!.instructions.count())
                method.addInstructions(
                    0,
                    """
                        new-instance v0, Landroidx/work/ListenableWorker${'$'}Result${'$'}Success;
                        invoke-direct {v0}, Landroidx/work/ListenableWorker${'$'}Result${'$'}Success;-><init>()V
                        return-object v0
                    """.trimIndent(),
                )
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(classType)}.$methodName")
            } else if ((methodName == "upload" || methodName.startsWith("doUpload")) &&
                method.returnType == "Lmozilla/telemetry/glean/internal/UploadResult;" &&
                method.implementation != null
            ) {
                method.clearTryBlocks()
                method.ensureRegisterCount(2)
                method.removeInstructions(0, method.implementation!!.instructions.count())
                method.addInstructions(
                    0,
                    """
                        new-instance v0, Lmozilla/telemetry/glean/internal/UploadResult${'$'}Done;
                        const/4 v1, 0x0
                        invoke-direct {v0, v1}, Lmozilla/telemetry/glean/internal/UploadResult${'$'}Done;-><init>(B)V
                        return-object v0
                    """.trimIndent(),
                )
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(classType)}.$methodName")
            }
        }
    }

    if (!foundAny) {
        println("[Block Telemetry & Trackers] Skipped: Mozilla Glean classes absent.")
    }
}

private fun BytecodePatchContext.hookCrashReporting(
    hookedMethods: MutableList<String>,
    hookedSignatures: MutableSet<String>,
) {
    val crashClasses = listOf(
        "Lmozilla/components/lib/crash/CrashReporter;",
        "Lorg/mozilla/fenix/crashreporting/FenixCrashReporter;",
        "Lorg/mozilla/geckoview/CrashReporter;",
    )

    var foundAny = false
    for (classType in crashClasses) {
        val classDef = classDefByOrNull(classType) ?: continue
        foundAny = true
        val mutableClass = mutableClassDefBy(classDef)

        for (method in mutableClass.methods) {
            val sig = "$classType->${method.name}"
            if (sig in hookedSignatures) continue

            if (method.name in setOf("submitReport", "sendCrashReport") && isSafeVoidDispatcher(method)) {
                method.addInstructions(0, "return-void")
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(classType)}.${method.name}")
            }
        }
    }

    if (!foundAny) {
        println("[Block Telemetry & Trackers] Skipped: Crash reporter classes absent.")
    }
}

// -- Hosts Rewrite Layer (Second-Layer Defense) ------------------------------

private const val SINK_HOST = "0.0.0.0"

private val DEFAULT_BLOCKED_HOSTS = setOf(
    // Mozilla Glean / Telemetry endpoints
    "incoming.telemetry.mozilla.org",
    "telemetry.mozilla.org",
    "telemetry-incoming.r53-2.services.mozilla.com",

    // Mozilla Crash Reporting (Socorro) endpoints
    "crash-reports.mozilla.com",
    "crash-stats.mozilla.com",

    // Adjust attribution endpoints
    "app.adjust.com",
    "app.adjust.net",
    "app.adjust.world",

    // Google / Measurement endpoints
    "app-measurement.com",
    "firebaselogging-pa.googleapis.com",
)

private val RESERVED_HOST_EXCLUSIONS = setOf(
    "localhost",
    "localhost6",
    "localhost.localdomain",
    "0.0.0.0",
    "127.0.0.1",
    "::1",
)

private val TELEMETRY_PACKAGE_PREFIXES = listOf(
    "Lmozilla/telemetry/",
    "Lmozilla/components/service/glean/",
    "Lmozilla/components/lib/crash/",
    "Lorg/mozilla/fenix/crashreporting/",
    "Lcom/adjust/sdk/",
    "Lcom/google/android/datatransport/",
    "Lcom/google/android/gms/measurement/",
)

private fun isTelemetryCandidateClass(type: String): Boolean {
    for (prefix in TELEMETRY_PACKAGE_PREFIXES) {
        if (type.startsWith(prefix)) return true
    }
    return false
}

private fun extractCandidateHost(raw: String): String {
    val withoutScheme = if (raw.contains("://")) raw.substringAfter("://") else raw
    return withoutScheme
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringBefore(':')
        .trim()
        .trimEnd('.')
}

private fun buildRewriteLiteral(literal: String, blockedHosts: Set<String>): Pair<String, String>? {
    if (literal.isBlank() || literal.length > 512) return null
    if (RESERVED_HOST_EXCLUSIONS.any { literal.equals(it, ignoreCase = true) }) return null

    val candidateHost = extractCandidateHost(literal)
    if (candidateHost.isEmpty() || candidateHost.length > 253) return null
    if (candidateHost in RESERVED_HOST_EXCLUSIONS) return null

    val matchedHost = blockedHosts.firstOrNull { blocked ->
        candidateHost.equals(blocked, ignoreCase = true) ||
            candidateHost.endsWith(".$blocked", ignoreCase = true)
    } ?: return null

    val replacement = literal.replace(candidateHost, SINK_HOST, ignoreCase = true)
    return Pair(replacement, matchedHost)
}

private data class PendingHostsRewrite(
    val index: Int,
    val register: Int,
    val replacement: String,
    val matchedRule: String,
)

private fun collectHostsRewrites(method: MutableMethod, blockedHosts: Set<String>): List<PendingHostsRewrite> {
    val instructions = method.instructionsOrNull?.toList() ?: return emptyList()
    val rewrites = mutableListOf<PendingHostsRewrite>()
    for ((index, instruction) in instructions.withIndex()) {
        val opcode = instruction.opcode
        if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) continue
        val ref = (instruction as? ReferenceInstruction)?.reference as? StringReference ?: continue
        val original = ref.string
        val (replacement, matchedRule) = buildRewriteLiteral(original, blockedHosts) ?: continue
        val register = (instruction as? OneRegisterInstruction)?.registerA ?: continue
        rewrites.add(PendingHostsRewrite(index, register, replacement, matchedRule))
    }
    return rewrites
}

private fun escapeSmaliLiteral(value: String): String =
    value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

private fun applyHostsRewrites(method: MutableMethod, rewrites: List<PendingHostsRewrite>) {
    for (rewrite in rewrites.sortedByDescending { it.index }) {
        val opcode = if (rewrite.register > 255) "const-string/jumbo" else "const-string"
        method.replaceInstruction(
            rewrite.index,
            "$opcode v${rewrite.register}, \"${escapeSmaliLiteral(rewrite.replacement)}\"",
        )
    }
}

private fun hasBlockedHostLiteral(instruction: Instruction, blockedHosts: Set<String>): Boolean {
    val opcode = instruction.opcode
    if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) return false
    val ref = (instruction as? ReferenceInstruction)?.reference as? StringReference ?: return false
    return buildRewriteLiteral(ref.string, blockedHosts) != null
}

private fun hasBlockedHostLiteral(method: Method, blockedHosts: Set<String>): Boolean =
    method.implementation?.instructions?.any { hasBlockedHostLiteral(it, blockedHosts) } == true

private fun hasBlockedHostLiteral(classDef: ClassDef, blockedHosts: Set<String>): Boolean =
    classDef.methods.any { hasBlockedHostLiteral(it, blockedHosts) }

private data class HostRewriteStats(
    val rewrittenStrings: Int,
    val ruleCounts: Map<String, Int>,
)

private fun BytecodePatchContext.blockHostsInDex(
    blockedHosts: Set<String>,
    touchedClasses: MutableSet<String>,
    predicate: ((String) -> Boolean)? = null,
): HostRewriteStats {
    var rewrittenStrings = 0
    val ruleCounts = mutableMapOf<String, Int>()

    classDefForEach { classDef ->
        if (predicate != null && !predicate(classDef.type)) return@classDefForEach
        if (!hasBlockedHostLiteral(classDef, blockedHosts)) return@classDefForEach

        val mutableClass = mutableClassDefBy(classDef)
        var classModified = false

        for (method in mutableClass.methods) {
            val rewrites = collectHostsRewrites(method, blockedHosts)
            if (rewrites.isNotEmpty()) {
                applyHostsRewrites(method, rewrites)
                rewrittenStrings += rewrites.size
                classModified = true
                for (rw in rewrites) {
                    ruleCounts[rw.matchedRule] = (ruleCounts[rw.matchedRule] ?: 0) + 1
                }
            }
        }

        if (classModified) {
            touchedClasses.add(classDef.type)
        }
    }

    return HostRewriteStats(rewrittenStrings, ruleCounts)
}

// -- Primary Bytecode Patch --------------------------------------------------

@Suppress("unused")
val firefoxBlockTelemetryPatch = bytecodePatch(
    name = "Block Telemetry & Trackers",
    description = "Neutralizes Adjust attribution and Mozilla Glean telemetry dispatchers, disables Socorro crash reporting, and rewrites telemetry endpoints to 0.0.0.0.",
    default = true,
) {
    compatibleWith(Constants.COMPATIBILITY_FIREFOX)
    dependsOn(firefoxTelemetryResourcePatch)

    val customBlockedHosts by stringOption(
        key = "custom-blocked-hosts",
        title = "Custom Blocked Hosts",
        description = "Comma-separated list of additional telemetry hostnames or domains to redirect to 0.0.0.0. Single-label, credentialed-URL, and IPv6 entries are skipped.",
        required = false,
    )

    execute {
        val hookedMethods = mutableListOf<String>()
        val hookedSignatures = mutableSetOf<String>()

        hookAdjustSdk(hookedMethods, hookedSignatures)
        hookGleanTelemetry(hookedMethods, hookedSignatures)
        hookCrashReporting(hookedMethods, hookedSignatures)

        val userHosts = mutableListOf<String>()
        val skippedUserHosts = mutableListOf<Pair<String, String>>()

        customBlockedHosts?.split(",")?.forEach { rawToken ->
            val sanitizedRaw = rawToken.replace("\r", " ").replace("\n", " ").trim()
            if (sanitizedRaw.isEmpty()) return@forEach

            if (sanitizedRaw.contains('@')) {
                skippedUserHosts.add(sanitizedRaw to "credentials/userinfo not supported")
                return@forEach
            }

            if (sanitizedRaw.startsWith('[') || sanitizedRaw.contains("::")) {
                skippedUserHosts.add(sanitizedRaw to "IPv6 literals not supported")
                return@forEach
            }

            val candidate = extractCandidateHost(sanitizedRaw.lowercase())
            if (candidate.startsWith('[') || candidate.contains("::") || candidate.count { it == ':' } > 1) {
                skippedUserHosts.add(sanitizedRaw to "IPv6 literals not supported")
                return@forEach
            }

            if (candidate.isEmpty() || candidate.length > 253) {
                skippedUserHosts.add(sanitizedRaw to "invalid host format")
                return@forEach
            }

            if (candidate in RESERVED_HOST_EXCLUSIONS) {
                skippedUserHosts.add(sanitizedRaw to "reserved host")
                return@forEach
            }

            if (!candidate.contains('.')) {
                skippedUserHosts.add(sanitizedRaw to "single-label domain not supported")
                return@forEach
            }

            userHosts.add(candidate)
        }

        if (skippedUserHosts.isNotEmpty()) {
            val skipDetails = skippedUserHosts.joinToString(", ") { "${it.first} (${it.second})" }
            println("[Block Telemetry & Trackers] Custom blocked hosts skipped (${skippedUserHosts.size}): $skipDetails.")
        }

        val distinctUserHosts = userHosts.distinct()
        val totalRuleCount = DEFAULT_BLOCKED_HOSTS.size + distinctUserHosts.size
        if (totalRuleCount > 100_000) {
            println("[Block Telemetry & Trackers] Warning: Blocklist contains $totalRuleCount rules. Memory usage and patching latency may be high.")
        }

        val touchedClassTypes = mutableSetOf<String>()

        // Pass 1: Scoped pass for default telemetry endpoints within telemetry package prefixes
        val defaultStats = blockHostsInDex(DEFAULT_BLOCKED_HOSTS, touchedClassTypes) { isTelemetryCandidateClass(it) }

        // Pass 2: Unscoped pass for user-supplied custom blocked hosts (scans all classes; only matching classes are mutated)
        val customStats = if (distinctUserHosts.isNotEmpty()) {
            blockHostsInDex(distinctUserHosts.toSet(), touchedClassTypes, predicate = null)
        } else {
            HostRewriteStats(0, emptyMap())
        }

        val totalRewritten = defaultStats.rewrittenStrings + customStats.rewrittenStrings
        val totalClasses = touchedClassTypes.size

        println("[Block Telemetry & Trackers] Neutralized ${hookedMethods.size} telemetry dispatch methods and rewrote $totalRewritten host literals across $totalClasses classes -> 0.0.0.0.")

        if (distinctUserHosts.isNotEmpty()) {
            val appliedUser = distinctUserHosts.filter { (customStats.ruleCounts[it] ?: 0) > 0 }
            val unappliedUser = distinctUserHosts.filter { (customStats.ruleCounts[it] ?: 0) == 0 }

            if (appliedUser.isNotEmpty()) {
                val appliedSummary = appliedUser.joinToString(", ") { "$it (${customStats.ruleCounts[it]} rewrites)" }
                println("[Block Telemetry & Trackers] Custom blocked hosts applied: $appliedSummary.")
            }
            if (unappliedUser.isNotEmpty()) {
                println("[Block Telemetry & Trackers] Custom blocked hosts unapplied (0 rewrites): ${unappliedUser.joinToString(", ")}.")
            }
        }
    }
}
