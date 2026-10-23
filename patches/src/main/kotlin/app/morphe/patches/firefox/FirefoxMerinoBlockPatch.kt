package app.morphe.patches.firefox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.instructionsOrNull
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.Constants
import app.morphe.patches.shared.cleanClassName
import app.morphe.patches.shared.replaceWithReturnBoolean
import app.morphe.patches.shared.replaceWithReturnEmptyList
import app.morphe.patches.shared.replaceWithReturnVoid
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val SINK_HOST = "0.0.0.0"

private val suggestMerinoClientInitFingerprint = Fingerprint(
    definingClass = "Lmozilla/components/feature/fxsuggest/client/SuggestMerinoClient;",
    strings = listOf("https://ohttp-merino.mozilla.fastly-edge.com"),
)

private val suggestionDetailsToStringFingerprint = Fingerprint(
    definingClass = "Lmozilla/components/feature/fxsuggest/SuggestionDetails;",
    strings = listOf("isSponsored="),
)

private val searchFragmentStateFingerprint = Fingerprint(
    strings = listOf("showNonSponsoredSuggestions="),
)

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
        returnType.contains("Deferred")
    ) {
        return false
    }
    val lower = method.name.lowercase()
    if (lower.contains("consent") ||
        lower.contains("optout") ||
        lower.contains("opt_out") ||
        lower.startsWith("delete") ||
        lower.startsWith("clear") ||
        lower.startsWith("reset")
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
    val lower = method.name.lowercase()
    if (lower.contains("consent") || lower.contains("optout") || lower.contains("opt_out")) {
        return false
    }
    return true
}

private fun isSafeListDispatcher(method: Method): Boolean {
    if (method.implementation == null) {
        return false
    }
    if (method.name.startsWith("<")) {
        return false
    }
    if (method.returnType != "Ljava/util/List;") {
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

private fun escapeSmaliLiteral(value: String): String =
    value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

private fun rewriteMerinoHostLiterals(method: MutableMethod): Int {
    val instructions = method.instructionsOrNull?.toList() ?: return 0
    var rewrites = 0
    for ((index, instruction) in instructions.withIndex().reversed()) {
        val opcode = instruction.opcode
        if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) continue
        val ref = (instruction as? ReferenceInstruction)?.reference as? StringReference ?: continue
        val str = ref.string
        if (str.contains("ohttp-merino.mozilla.fastly-edge.com", ignoreCase = true) ||
            str.contains("merino.services.mozilla.com", ignoreCase = true)
        ) {
            val register = (instruction as? OneRegisterInstruction)?.registerA ?: continue
            val replacement = if (str.contains("://")) {
                val prefix = str.substringBefore("://") + "://"
                val rest = str.substringAfter("://")
                val path = if (rest.contains("/")) "/" + rest.substringAfter("/") else ""
                "$prefix$SINK_HOST$path"
            } else {
                SINK_HOST
            }
            val smaliOpcode = if (register > 255) "const-string/jumbo" else "const-string"
            method.replaceInstruction(index, "$smaliOpcode v$register, \"${escapeSmaliLiteral(replacement)}\"")
            rewrites++
        }
    }
    return rewrites
}

private fun BytecodePatchContext.hookSuggestMerinoClient(
    hookedMethods: MutableList<String>,
    hookedSignatures: MutableSet<String>,
    touchedClasses: MutableSet<String>,
): Pair<Int, Int> {
    var hooks = 0
    var rewrites = 0
    val classType = "Lmozilla/components/feature/fxsuggest/client/SuggestMerinoClient;"
    val classDef = classDefByOrNull(classType)
    if (classDef == null) {
        println("[Firefox Merino Block] Skipped: SuggestMerinoClient class absent.")
        return Pair(0, 0)
    }

    val mutableClass = mutableClassDefBy(classDef)
    var classModified = false

    for (method in mutableClass.methods) {
        val sig = "$classType->${method.name}:${method.returnType}"

        val methodRewrites = rewriteMerinoHostLiterals(method)
        if (methodRewrites > 0) {
            rewrites += methodRewrites
            classModified = true
        }

        if (sig in hookedSignatures) continue
        if (method.name.startsWith("<")) continue

        when {
            method.returnType == "Z" && isSafeBooleanDispatcher(method) -> {
                method.replaceWithReturnBoolean(false)
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(classType)}.${method.name} -> false")
                hooks++
                classModified = true
            }
            method.returnType == "Ljava/util/List;" && isSafeListDispatcher(method) -> {
                method.replaceWithReturnEmptyList()
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(classType)}.${method.name} -> emptyList")
                hooks++
                classModified = true
            }
            method.returnType == "V" && isSafeVoidDispatcher(method) -> {
                method.replaceWithReturnVoid()
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(classType)}.${method.name} -> void")
                hooks++
                classModified = true
            }
        }
    }

    if (classModified) {
        touchedClasses.add(classType)
    }

    return Pair(hooks, rewrites)
}

private fun BytecodePatchContext.hookSuggestionDetails(
    hookedMethods: MutableList<String>,
    hookedSignatures: MutableSet<String>,
    touchedClasses: MutableSet<String>,
): Int {
    var hooks = 0
    val classType = "Lmozilla/components/feature/fxsuggest/SuggestionDetails;"
    val classDef = classDefByOrNull(classType)
    if (classDef == null) {
        println("[Firefox Merino Block] Skipped: SuggestionDetails class absent.")
        return 0
    }

    val mutableClass = mutableClassDefBy(classDef)
    var classModified = false

    for (method in mutableClass.methods) {
        val sig = "$classType->${method.name}:${method.returnType}"
        if (sig in hookedSignatures) continue
        if (method.name.startsWith("<")) continue

        if (method.returnType == "Z" && isSafeBooleanDispatcher(method)) {
            val nameLower = method.name.lowercase()
            if (nameLower == "issponsored" ||
                nameLower == "getissponsored" ||
                (nameLower.contains("sponsored") && !nameLower.contains("nonsponsored"))
            ) {
                method.replaceWithReturnBoolean(false)
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(classType)}.${method.name} -> false")
                hooks++
                classModified = true
            }
        }
    }

    if (classModified) {
        touchedClasses.add(classType)
    }

    return hooks
}

private fun BytecodePatchContext.hookGleanSponsoredMetrics(
    hookedMethods: MutableList<String>,
    hookedSignatures: MutableSet<String>,
    touchedClasses: MutableSet<String>,
): Int {
    val gleanAwesomebarClasses = listOf(
        "Lorg/mozilla/fenix/GleanMetrics\$Awesomebar\$SponsoredSuggestionClickedExtra;",
        "Lorg/mozilla/fenix/GleanMetrics\$Awesomebar\$SponsoredSuggestionImpressedExtra;",
        "Lmozilla/telemetry/glean/GleanMetrics\$Awesomebar\$SponsoredSuggestionClickedExtra;",
        "Lmozilla/telemetry/glean/GleanMetrics\$Awesomebar\$SponsoredSuggestionImpressedExtra;",
        "Lmozilla/components/service/glean/GleanMetrics\$Awesomebar\$SponsoredSuggestionClickedExtra;",
        "Lmozilla/components/service/glean/GleanMetrics\$Awesomebar\$SponsoredSuggestionImpressedExtra;",
    )

    var hooks = 0
    var foundAny = false

    for (classType in gleanAwesomebarClasses) {
        val classDef = classDefByOrNull(classType) ?: continue
        foundAny = true
        val mutableClass = mutableClassDefBy(classDef)
        var classModified = false

        for (method in mutableClass.methods) {
            val sig = "$classType->${method.name}:${method.returnType}"
            if (sig in hookedSignatures) continue
            if (method.name.startsWith("<")) continue

            if (isSafeVoidDispatcher(method)) {
                method.replaceWithReturnVoid()
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(classType)}.${method.name} -> void")
                hooks++
                classModified = true
            }
        }

        if (classModified) {
            touchedClasses.add(classType)
        }
    }

    if (!foundAny) {
        println("[Firefox Merino Block] Skipped: GleanMetrics Awesomebar sponsored extra classes absent.")
    }

    return hooks
}

private fun BytecodePatchContext.hookSearchFragmentState(
    hookedMethods: MutableList<String>,
    hookedSignatures: MutableSet<String>,
    touchedClasses: MutableSet<String>,
): Int {
    var hooks = 0
    val matches = searchFragmentStateFingerprint.matchAllOrNull() ?: emptyList()

    val candidateClasses = mutableSetOf<String>()
    for (match in matches) {
        candidateClasses.add(match.method.definingClass)
    }

    val fallbackClasses = listOf(
        "Lorg/mozilla/fenix/search/SearchFragmentState;",
        "Lorg/mozilla/fenix/awesomebar/SearchFragmentState;",
    )
    for (fb in fallbackClasses) {
        if (classDefByOrNull(fb) != null) {
            candidateClasses.add(fb)
        }
    }

    if (candidateClasses.isEmpty()) {
        println("[Firefox Merino Block] Skipped: SearchFragmentState class absent.")
        return 0
    }

    for (classType in candidateClasses) {
        val classDef = classDefByOrNull(classType) ?: continue
        val mutableClass = mutableClassDefBy(classDef)
        var classModified = false

        for (method in mutableClass.methods) {
            val sig = "$classType->${method.name}:${method.returnType}"
            if (sig in hookedSignatures) continue
            if (method.name.startsWith("<")) continue

            if (method.returnType == "Z" && isSafeBooleanDispatcher(method)) {
                val nameLower = method.name.lowercase()
                if (nameLower.contains("showsponsored") ||
                    (nameLower.contains("sponsored") && !nameLower.contains("nonsponsored") &&
                        (nameLower.contains("show") || nameLower.contains("display") || nameLower.startsWith("get")))
                ) {
                    method.replaceWithReturnBoolean(false)
                    hookedSignatures.add(sig)
                    hookedMethods.add("${cleanClassName(classType)}.${method.name} -> false")
                    hooks++
                    classModified = true
                }
            }
        }

        if (classModified) {
            touchedClasses.add(classType)
        }
    }

    return hooks
}

@Suppress("unused")
val firefoxMerinoBlockPatch = bytecodePatch(
    name = "Firefox Merino Block",
    description = "Neutralizes Firefox Suggest Merino client, suppresses sponsored suggestion display in search, disables sponsored suggestion Glean telemetry, and redirects Merino endpoints to 0.0.0.0. Breaks remote sponsored suggestions and remote top-sites.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY_FIREFOX)

    execute {
        val hookedMethods = mutableListOf<String>()
        val hookedSignatures = mutableSetOf<String>()
        val touchedClasses = mutableSetOf<String>()

        val (merinoHooks, merinoRewrites) = hookSuggestMerinoClient(hookedMethods, hookedSignatures, touchedClasses)
        val detailsHooks = hookSuggestionDetails(hookedMethods, hookedSignatures, touchedClasses)
        val gleanHooks = hookGleanSponsoredMetrics(hookedMethods, hookedSignatures, touchedClasses)
        val searchStateHooks = hookSearchFragmentState(hookedMethods, hookedSignatures, touchedClasses)

        val totalHooks = merinoHooks + detailsHooks + gleanHooks + searchStateHooks
        val totalClasses = touchedClasses.size

        println("[Firefox Merino Block] Neutralized $totalHooks suggestion methods and rewrote $merinoRewrites Merino host literals across $totalClasses classes -> 0.0.0.0.")
    }
}
