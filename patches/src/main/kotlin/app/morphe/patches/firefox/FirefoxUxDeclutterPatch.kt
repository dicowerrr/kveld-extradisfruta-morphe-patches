package app.morphe.patches.firefox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.Constants
import app.morphe.patches.shared.cleanClassName
import app.morphe.patches.shared.clearTryBlocks
import app.morphe.patches.shared.ensureRegisterCount
import app.morphe.patches.shared.replaceWithReturnBoolean
import app.morphe.patches.shared.replaceWithReturnBooleanObject
import app.morphe.patches.shared.replaceWithReturnIntegerObject
import app.morphe.patches.shared.replaceWithReturnVoid
import com.android.tools.smali.dexlib2.iface.Method

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

private fun isSafeIntDispatcher(method: Method): Boolean {
    if (method.implementation == null) {
        return false
    }
    if (method.name.startsWith("<")) {
        return false
    }
    if (method.returnType != "I") {
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

/**
 * Safely replaces method body with a 32-bit const integer return instruction.
 * Standard replaceWithReturnInt uses const/4 which fails for literals outside -8..7.
 */
private fun MutableMethod.replaceWithReturnIntConst(value: Int) {
    val impl = implementation ?: return
    clearTryBlocks()
    ensureRegisterCount(1)
    removeInstructions(0, impl.instructions.count())
    addInstructions(0, "const v0, $value\nreturn v0")
}

private val fenixOnboardingPrefFingerprint = Fingerprint(
    strings = listOf("fenix.onboarding.last_version"),
)

private val onboardedVersionFingerprint = Fingerprint(
    strings = listOf("onboardedVersion"),
)

private val initialOnboardingNotCompletedFingerprint = Fingerprint(
    definingClass = "Lorg/mozilla/fenix/onboarding/continuous/ContinuousOnboardingFeature;",
    strings = listOf("Initial onboarding has not been completed"),
)

private fun BytecodePatchContext.hookFenixOnboarding(
    hookedMethods: MutableList<String>,
    hookedSignatures: MutableSet<String>,
): Int {
    val fenixOnboardingClasses = listOf(
        "Lorg/mozilla/fenix/onboarding/FenixOnboarding;",
        "Lorg/mozilla/fenix/onboarding/FenixOnboarding\$Companion;",
    )
    var count = 0

    for (classType in fenixOnboardingClasses) {
        val classDef = classDefByOrNull(classType) ?: continue
        val mutableClass = mutableClassDefBy(classDef)

        for (method in mutableClass.methods) {
            val sig = "$classType->${method.name}"
            if (sig in hookedSignatures) continue
            if (method.name.startsWith("<")) continue

            when {
                method.name == "userHasBeenOnboarded" -> {
                    if (method.returnType == "Z") {
                        method.replaceWithReturnBoolean(true)
                        hookedSignatures.add(sig)
                        hookedMethods.add("${cleanClassName(classType)}.userHasBeenOnboarded -> true")
                        count++
                    } else if (method.returnType == "Ljava/lang/Boolean;") {
                        method.replaceWithReturnBooleanObject(true)
                        hookedSignatures.add(sig)
                        hookedMethods.add("${cleanClassName(classType)}.userHasBeenOnboarded -> Boolean.TRUE")
                        count++
                    }
                }
                method.name == "getOnboardedVersion" -> {
                    when (method.returnType) {
                        "I" -> {
                            method.replaceWithReturnIntConst(Int.MAX_VALUE)
                            hookedSignatures.add(sig)
                            hookedMethods.add("${cleanClassName(classType)}.getOnboardedVersion -> Int.MAX_VALUE")
                            count++
                        }
                        "Z" -> {
                            method.replaceWithReturnBoolean(true)
                            hookedSignatures.add(sig)
                            hookedMethods.add("${cleanClassName(classType)}.getOnboardedVersion -> true")
                            count++
                        }
                        "J" -> {
                            method.clearTryBlocks()
                            method.ensureRegisterCount(2)
                            method.removeInstructions(0, method.implementation?.instructions?.count() ?: 0)
                            method.addInstructions(0, "const-wide/16 v0, 0x7fff\nreturn-wide v0")
                            hookedSignatures.add(sig)
                            hookedMethods.add("${cleanClassName(classType)}.getOnboardedVersion -> Long.MAX")
                            count++
                        }
                        "Ljava/lang/Integer;" -> {
                            method.replaceWithReturnIntegerObject(Int.MAX_VALUE)
                            hookedSignatures.add(sig)
                            hookedMethods.add("${cleanClassName(classType)}.getOnboardedVersion -> Integer.MAX_VALUE")
                            count++
                        }
                    }
                }
                isSafeBooleanDispatcher(method) -> {
                    if (method.name.contains("show", ignoreCase = true) || method.name.contains("should", ignoreCase = true)) {
                        method.replaceWithReturnBoolean(false)
                        hookedSignatures.add(sig)
                        hookedMethods.add("${cleanClassName(classType)}.${method.name} -> false")
                        count++
                    } else if (method.name.contains("onboard", ignoreCase = true) ||
                        method.name.contains("seen", ignoreCase = true) ||
                        method.name.contains("complete", ignoreCase = true) ||
                        method.name.startsWith("is") ||
                        method.name.startsWith("has")
                    ) {
                        method.replaceWithReturnBoolean(true)
                        hookedSignatures.add(sig)
                        hookedMethods.add("${cleanClassName(classType)}.${method.name} -> true")
                        count++
                    }
                }
                isSafeIntDispatcher(method) -> {
                    if (method.name.contains("version", ignoreCase = true) || method.name.contains("onboard", ignoreCase = true)) {
                        method.replaceWithReturnIntConst(Int.MAX_VALUE)
                        hookedSignatures.add(sig)
                        hookedMethods.add("${cleanClassName(classType)}.${method.name} -> Int.MAX_VALUE")
                        count++
                    }
                }
            }
        }
    }

    return count
}

private fun BytecodePatchContext.hookPrefStringAnchors(
    hookedMethods: MutableList<String>,
    hookedSignatures: MutableSet<String>,
): Int {
    var count = 0

    // Anchor 1: fenix.onboarding.last_version
    val prefMatches = fenixOnboardingPrefFingerprint.matchAllOrNull() ?: emptyList()
    for (match in prefMatches) {
        val method = match.method
        val sig = "${method.definingClass}->${method.name}"
        if (sig in hookedSignatures) continue
        if (method.name.startsWith("<")) continue

        when {
            method.returnType == "I" && isSafeIntDispatcher(method) -> {
                method.replaceWithReturnIntConst(Int.MAX_VALUE)
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(method.definingClass)}.${method.name} -> Int.MAX_VALUE")
                count++
            }
            method.returnType == "Z" && isSafeBooleanDispatcher(method) -> {
                val shouldReturnFalse = method.name.contains("show", ignoreCase = true) ||
                    method.name.contains("should", ignoreCase = true)
                method.replaceWithReturnBoolean(!shouldReturnFalse)
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(method.definingClass)}.${method.name} -> ${!shouldReturnFalse}")
                count++
            }
            method.returnType == "Ljava/lang/Integer;" -> {
                method.replaceWithReturnIntegerObject(Int.MAX_VALUE)
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(method.definingClass)}.${method.name} -> Integer.MAX_VALUE")
                count++
            }
        }
    }

    // Anchor 2: onboardedVersion
    val versionMatches = onboardedVersionFingerprint.matchAllOrNull() ?: emptyList()
    for (match in versionMatches) {
        val method = match.method
        val sig = "${method.definingClass}->${method.name}"
        if (sig in hookedSignatures) continue
        if (method.name.startsWith("<")) continue

        when {
            method.returnType == "I" && isSafeIntDispatcher(method) -> {
                method.replaceWithReturnIntConst(Int.MAX_VALUE)
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(method.definingClass)}.${method.name} -> Int.MAX_VALUE")
                count++
            }
            method.returnType == "Z" && isSafeBooleanDispatcher(method) -> {
                val shouldReturnFalse = method.name.contains("show", ignoreCase = true) ||
                    method.name.contains("should", ignoreCase = true)
                method.replaceWithReturnBoolean(!shouldReturnFalse)
                hookedSignatures.add(sig)
                hookedMethods.add("${cleanClassName(method.definingClass)}.${method.name} -> ${!shouldReturnFalse}")
                count++
            }
        }
    }

    // Anchor 3: Initial onboarding has not been completed
    val continuousMatches = initialOnboardingNotCompletedFingerprint.matchAllOrNull() ?: emptyList()
    for (match in continuousMatches) {
        val method = match.method
        val sig = "${method.definingClass}->${method.name}"
        if (sig in hookedSignatures) continue
        if (method.name.startsWith("<")) continue

        if (method.returnType == "Z" && isSafeBooleanDispatcher(method)) {
            method.replaceWithReturnBoolean(true)
            hookedSignatures.add(sig)
            hookedMethods.add("${cleanClassName(method.definingClass)}.${method.name} -> true")
            count++
        } else if (method.returnType == "V" && isSafeVoidDispatcher(method)) {
            method.replaceWithReturnVoid()
            hookedSignatures.add(sig)
            hookedMethods.add("${cleanClassName(method.definingClass)}.${method.name} -> return-void")
            count++
        }
    }

    return count
}

private fun BytecodePatchContext.hookOnboardingTelemetry(
    hookedMethods: MutableList<String>,
    hookedSignatures: MutableSet<String>,
): Int {
    val classType = "Lorg/mozilla/fenix/onboarding/OnboardingTelemetryRecorder;"
    val classDef = classDefByOrNull(classType)
    if (classDef == null) {
        println("[Firefox UX Declutter] Skipped: OnboardingTelemetryRecorder class absent.")
        return 0
    }

    val mutableClass = mutableClassDefBy(classDef)
    var count = 0

    for (method in mutableClass.methods) {
        val sig = "$classType->${method.name}"
        if (sig in hookedSignatures) continue

        if (isSafeVoidDispatcher(method)) {
            method.replaceWithReturnVoid()
            hookedSignatures.add(sig)
            hookedMethods.add("OnboardingTelemetryRecorder.${method.name}")
            count++
        }
    }

    return count
}

private fun BytecodePatchContext.hookContinuousOnboarding(
    hookedMethods: MutableList<String>,
    hookedSignatures: MutableSet<String>,
): Int {
    val classType = "Lorg/mozilla/fenix/onboarding/continuous/ContinuousOnboardingFeature;"
    val classDef = classDefByOrNull(classType)
    if (classDef == null) {
        println("[Firefox UX Declutter] Skipped: ContinuousOnboardingFeature class absent.")
        return 0
    }

    val mutableClass = mutableClassDefBy(classDef)
    var count = 0

    for (method in mutableClass.methods) {
        val sig = "$classType->${method.name}"
        if (sig in hookedSignatures) continue

        when (method.name) {
            "start" -> {
                if (isSafeVoidDispatcher(method)) {
                    method.replaceWithReturnVoid()
                    hookedSignatures.add(sig)
                    hookedMethods.add("ContinuousOnboardingFeature.start")
                    count++
                }
            }
            "showDialog" -> {
                if (method.returnType == "V" && isSafeVoidDispatcher(method)) {
                    method.replaceWithReturnVoid()
                    hookedSignatures.add(sig)
                    hookedMethods.add("ContinuousOnboardingFeature.showDialog")
                    count++
                } else if (method.returnType == "Z" && isSafeBooleanDispatcher(method)) {
                    method.replaceWithReturnBoolean(false)
                    hookedSignatures.add(sig)
                    hookedMethods.add("ContinuousOnboardingFeature.showDialog")
                    count++
                }
            }
        }
    }

    return count
}

private fun BytecodePatchContext.hookWallpaperOnboarding(
    hookedMethods: MutableList<String>,
    hookedSignatures: MutableSet<String>,
): Int {
    val classType = "Lorg/mozilla/fenix/onboarding/WallpaperOnboardingDialogFragment;"
    val classDef = classDefByOrNull(classType)
    if (classDef == null) {
        println("[Firefox UX Declutter] Skipped: WallpaperOnboardingDialogFragment class absent.")
        return 0
    }

    val mutableClass = mutableClassDefBy(classDef)
    var count = 0

    for (method in mutableClass.methods) {
        val sig = "$classType->${method.name}"
        if (sig in hookedSignatures) continue

        if (method.name == "show" && isSafeVoidDispatcher(method)) {
            method.replaceWithReturnVoid()
            hookedSignatures.add(sig)
            hookedMethods.add("WallpaperOnboardingDialogFragment.show")
            count++
        }
    }

    return count
}

@Suppress("unused")
val firefoxUxDeclutterPatch = bytecodePatch(
    name = "UX Declutter",
    description = "Skips the first-run onboarding carousel and welcome flow, and suppresses continuous onboarding dialogs and promotional prompts.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY_FIREFOX)

    val skipFirstRun by booleanOption(
        key = "skipFirstRun",
        default = false,
        title = "Skip First-Run Onboarding",
        description = "Marks onboarding as completed so Firefox launches directly into the home screen/browser without displaying the welcome carousel, default browser prompt, or privacy tour.",
    )

    val suppressContinuousPromos by booleanOption(
        key = "suppressContinuousPromos",
        default = false,
        title = "Suppress Continuous Onboarding & Promos",
        description = "Neutralizes ContinuousOnboardingFeature startup and dialog triggers to prevent background default browser nags, wallpaper prompts, and feature callouts.",
    )

    execute {
        if (skipFirstRun != true && suppressContinuousPromos != true) {
            println("[Firefox UX Declutter] Skipped: All options disabled.")
            return@execute
        }

        val hookedMethods = mutableListOf<String>()
        val hookedSignatures = mutableSetOf<String>()
        var patched = 0

        if (skipFirstRun == true) {
            patched += hookFenixOnboarding(hookedMethods, hookedSignatures)
            patched += hookPrefStringAnchors(hookedMethods, hookedSignatures)
            patched += hookOnboardingTelemetry(hookedMethods, hookedSignatures)
        }

        if (suppressContinuousPromos == true) {
            patched += hookContinuousOnboarding(hookedMethods, hookedSignatures)
            patched += hookWallpaperOnboarding(hookedMethods, hookedSignatures)
        }

        val summaryDetails = if (hookedMethods.isNotEmpty()) {
            " (${hookedMethods.joinToString(", ")})"
        } else {
            ""
        }
        println("[Firefox UX Declutter] Applied $patched hooks$summaryDetails -> declutter active.")
    }
}
