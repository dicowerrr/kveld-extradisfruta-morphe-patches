package app.morphe.patches.firefox

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.Constants
import app.morphe.patches.shared.replaceWithReturnBoolean
import com.android.tools.smali.dexlib2.iface.Method
import org.w3c.dom.Element

private val firefoxCompatResourcePatch = resourcePatch(
    name = "Firefox Compatibility Manifest Normalization",
    description = "Strips sharedUserId and sharedUserLabel attributes from AndroidManifest.xml if present to avoid install-time signature conflicts.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY_FIREFOX)

    execute {
        val manifestFile = get("AndroidManifest.xml")
        if (!manifestFile.exists()) {
            println("[Firefox Compat Bypass] Skipped: AndroidManifest.xml not found.")
            return@execute
        }

        var stripped = 0
        document(manifestFile.absolutePath).use { doc ->
            val manifest = doc.getElementsByTagName("manifest").item(0) as? Element ?: run {
                println("[Firefox Compat Bypass] Skipped: manifest root element not found.")
                return@execute
            }

            if (manifest.hasAttribute("android:sharedUserId")) {
                manifest.removeAttribute("android:sharedUserId")
                stripped++
            }
            if (manifest.hasAttribute("android:sharedUserLabel")) {
                manifest.removeAttribute("android:sharedUserLabel")
                stripped++
            }
        }

        if (stripped > 0) {
            println("[Firefox Compat Bypass] Stripped $stripped sharedUser attribute(s) from AndroidManifest.xml.")
        } else {
            println("[Firefox Compat Bypass] AndroidManifest.xml verified: no sharedUserId attribute declared.")
        }
    }
}

private fun isSafeBooleanGetter(method: Method): Boolean {
    if (method.implementation == null) return false
    if (method.name == "<init>" || method.name == "<clinit>") return false
    if (method.returnType != "Z") return false
    val callbackParams = method.parameterTypes.map { it.toString() }.filter { param ->
        param.contains("Callback", ignoreCase = true) ||
            param.contains("Listener", ignoreCase = true) ||
            param.contains("Continuation", ignoreCase = true) ||
            param.contains("Observer", ignoreCase = true) ||
            param.contains("Handler", ignoreCase = true)
    }
    return callbackParams.isEmpty()
}

private fun isSafeVoidDispatcher(method: Method): Boolean {
    if (method.implementation == null) return false
    if (method.name == "<init>" || method.name == "<clinit>") return false
    if (method.returnType != "V") return false
    val callbackParams = method.parameterTypes.map { it.toString() }.filter { param ->
        param.contains("Callback", ignoreCase = true) ||
            param.contains("Listener", ignoreCase = true) ||
            param.contains("Continuation", ignoreCase = true) ||
            param.contains("Observer", ignoreCase = true) ||
            param.contains("Handler", ignoreCase = true)
    }
    return callbackParams.isEmpty()
}

private fun BytecodePatchContext.neutralizeEnvironmentChecks(
    hookedMethods: MutableList<String>,
): Int {
    var hooks = 0

    // Neutralize client-side debug/root assertions if present in environment check utilities
    val candidateClasses = listOf(
        "Lorg/mozilla/fenix/utils/EnvironmentUtils;",
        "Lorg/mozilla/fenix/debug/DebugUtils;",
        "Lmozilla/components/support/base/utils/EnvironmentUtils;",
    )

    for (classType in candidateClasses) {
        val classDef = classDefByOrNull(classType) ?: continue
        val mutableClass = mutableClassDefBy(classDef)

        for (method in mutableClass.methods) {
            if (method.name in setOf("isRooted", "isEmulator", "isDebuggerAttached") &&
                isSafeBooleanGetter(method)
            ) {
                // Polarity rule: adverse state detectors must be forced to false (0x0)
                method.replaceWithReturnBoolean(false)
                hookedMethods.add("$classType->${method.name} -> false")
                hooks++
            }
        }
    }

    return hooks
}

@Suppress("unused")
val firefoxCompatBypassPatch = bytecodePatch(
    name = "Bypass Compatibility Checks",
    description = "Normalizes manifest sharedUserId attributes and neutralizes client-side environment checks so Firefox runs reliably across sideloaded and custom environments.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY_FIREFOX)
    dependsOn(firefoxCompatResourcePatch)

    execute {
        val hookedMethods = mutableListOf<String>()

        val envHooks = neutralizeEnvironmentChecks(hookedMethods)
        val patched = envHooks

        if (hookedMethods.isNotEmpty()) {
            println("[Firefox Compat Bypass] Applied $patched hooks: ${hookedMethods.joinToString(", ")}.")
        }
        println("[Firefox Compat Bypass] Applied $patched hooks -> client-side compatibility checks bypassed.")
    }
}
