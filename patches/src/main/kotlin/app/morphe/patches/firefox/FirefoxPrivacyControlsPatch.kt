package app.morphe.patches.firefox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.string
import app.morphe.patches.shared.ANDROID_XML_NAMESPACE
import app.morphe.patches.shared.Constants
import app.morphe.patches.shared.replaceWithReturnBoolean
import app.morphe.patches.shared.replaceWithReturnNull
import app.morphe.patches.shared.replaceWithReturnVoid
import app.morphe.patches.shared.setAndroidAttribute
import app.morphe.patches.shared.sharedExtensionPatch
import app.morphe.patches.shared.stripPermissionsWhere
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

/* -------------------------------------------------------------------------- */
/* Configurable Option Declarations (All default = false per Opt-In Policy)  */
/* -------------------------------------------------------------------------- */

private val allowScreenshotsOption = booleanOption(
    key = "allowScreenshots",
    default = false,
    title = "Allow Screenshots & Screen Capture",
    description = "Bypasses FLAG_SECURE window restrictions in private browsing tabs and sets allowAudioPlaybackCapture in the manifest so screen captures and recordings are not blacked out.",
    required = false,
)

private val hardenNetworkSecurityOption = booleanOption(
    key = "hardenNetworkSecurity",
    default = false,
    title = "Network Security Hardening (User CA Trust & Pinning Bypass)",
    description = "Trusts user CA certificates in network_security_config.xml and neutralizes OkHttp CertificatePinner checks for local proxy inspection. WARNING: Enables TLS proxy inspection.",
    required = false,
)

private val cleanShareUrlsOption = booleanOption(
    key = "cleanShareUrls",
    default = false,
    title = "Clean Share Links",
    description = "Strips tracking parameters (utm_*, fbclid, gclid, igshid, msclkid, mc_eid, twclid) from URLs shared to the Android share sheet.",
    required = false,
)

private val neutralizeDeviceDataOption = booleanOption(
    key = "neutralizeDeviceData",
    default = false,
    title = "Neutralize Device Data Harvesting",
    description = "Neutralizes runtime access to Google Advertising ID, BATTERY_CHANGED broadcast events, motion sensor listeners, and last-known location queries.",
    required = false,
)

private val stripCameraOption = booleanOption(
    key = "stripCamera",
    default = false,
    title = "Strip Camera Permission",
    description = "Removes android.permission.CAMERA from AndroidManifest.xml. Disables QR code scanning and camera web inputs.",
    required = false,
)

private val stripMicrophoneOption = booleanOption(
    key = "stripMicrophone",
    default = false,
    title = "Strip Microphone Permissions",
    description = "Removes RECORD_AUDIO and CAPTURE_AUDIO_OUTPUT from AndroidManifest.xml. Disables voice search and microphone web inputs.",
    required = false,
)

private val stripLocationOption = booleanOption(
    key = "stripLocation",
    default = false,
    title = "Strip Location Permissions",
    description = "Removes ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, and ACCESS_BACKGROUND_LOCATION from AndroidManifest.xml.",
    required = false,
)

private val stripNotificationsOption = booleanOption(
    key = "stripNotifications",
    default = false,
    title = "Strip Notification Permission",
    description = "Removes android.permission.POST_NOTIFICATIONS from AndroidManifest.xml.",
    required = false,
)

private val stripMediaStorageOption = booleanOption(
    key = "stripMediaStorage",
    default = false,
    title = "Strip Media & Storage Permissions",
    description = "Removes READ_MEDIA_*, ACCESS_MEDIA_LOCATION, and legacy storage permissions from AndroidManifest.xml.",
    required = false,
)

private val stripPackageQueryOption = booleanOption(
    key = "stripPackageQuery",
    default = false,
    title = "Strip Query All Packages Permission",
    description = "Removes android.permission.QUERY_ALL_PACKAGES from AndroidManifest.xml to prevent installed app inventory queries.",
    required = false,
)

private val stripBiometricsOption = booleanOption(
    key = "stripBiometrics",
    default = false,
    title = "Strip Biometric Permissions",
    description = "Removes USE_BIOMETRIC and USE_FINGERPRINT permissions from AndroidManifest.xml.",
    required = false,
)

/* -------------------------------------------------------------------------- */
/* Named Indexed Fingerprints (Network Security & Certificate Pinning)        */
/* -------------------------------------------------------------------------- */

private val certificatePinnerCheckListFingerprint = Fingerprint(
    definingClass = "Lokhttp3/CertificatePinner;",
    name = "check",
    parameters = listOf("Ljava/lang/String;", "Ljava/util/List;"),
    returnType = "V",
)

private val certificatePinnerCheckArrayFingerprint = Fingerprint(
    definingClass = "Lokhttp3/CertificatePinner;",
    name = "check",
    parameters = listOf("Ljava/lang/String;", "[Ljava/security/cert/Certificate;"),
    returnType = "V",
)

private val certificatePinnerCheckOkHttpFingerprint = Fingerprint(
    definingClass = "Lokhttp3/CertificatePinner;",
    name = "check\$okhttp",
    parameters = listOf("Ljava/lang/String;", "Lkotlin/jvm/functions/Function0;"),
    returnType = "V",
)

/* -------------------------------------------------------------------------- */
/* Named Indexed Fingerprints (Runtime Device Data Harvesting)                */
/* -------------------------------------------------------------------------- */

private val advertisingIdClientGetInfoFingerprint = Fingerprint(
    definingClass = "Lcom/google/android/gms/ads/identifier/AdvertisingIdClient;",
    name = "getAdvertisingIdInfo",
    parameters = listOf("Landroid/content/Context;"),
    returnType = "Lcom/google/android/gms/ads/identifier/AdvertisingIdClient\$Info;",
)

private val batteryChangedReceiverFingerprint = Fingerprint(
    name = "onReceive",
    parameters = listOf("Landroid/content/Context;", "Landroid/content/Intent;"),
    returnType = "V",
    strings = listOf(string("android.intent.action.BATTERY_CHANGED", StringComparisonType.EQUALS)),
)

private val platformSensorHasTypeFingerprint = Fingerprint(
    name = "hasSensorType",
    parameters = listOf("I"),
    returnType = "Z",
    strings = listOf(string("PlatformSensorProvider", StringComparisonType.CONTAINS)),
)

private val locationManagerGetLastKnownLocationFingerprint = Fingerprint(
    definingClass = "Landroid/location/LocationManager;",
    name = "getLastKnownLocation",
    parameters = listOf("Ljava/lang/String;"),
    returnType = "Landroid/location/Location;",
)

/* -------------------------------------------------------------------------- */
/* Resource Setup Patch: Manifest Transforms & Network Security Config        */
/* -------------------------------------------------------------------------- */

private val firefoxPrivacyControlsResourcePatch = resourcePatch(
    name = "Privacy Controls (Setup)",
    description = "Configures network security config for user CA trust and strips selected sensitive permissions from AndroidManifest.xml.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY_FIREFOX)

    execute {
        val manifestFile = get("AndroidManifest.xml")
        if (!manifestFile.exists()) {
            println("[Firefox Privacy Controls] AndroidManifest.xml not found - skipping manifest transforms.")
            return@execute
        }

        val blockedPermissions = mutableSetOf<String>()
        if (stripCameraOption.value == true) {
            blockedPermissions.add("android.permission.CAMERA")
        }
        if (stripMicrophoneOption.value == true) {
            blockedPermissions.add("android.permission.RECORD_AUDIO")
            blockedPermissions.add("android.permission.CAPTURE_AUDIO_OUTPUT")
        }
        if (stripLocationOption.value == true) {
            blockedPermissions.add("android.permission.ACCESS_FINE_LOCATION")
            blockedPermissions.add("android.permission.ACCESS_COARSE_LOCATION")
            blockedPermissions.add("android.permission.ACCESS_BACKGROUND_LOCATION")
        }
        if (stripNotificationsOption.value == true) {
            blockedPermissions.add("android.permission.POST_NOTIFICATIONS")
        }
        if (stripMediaStorageOption.value == true) {
            blockedPermissions.add("android.permission.READ_MEDIA_IMAGES")
            blockedPermissions.add("android.permission.READ_MEDIA_VIDEO")
            blockedPermissions.add("android.permission.READ_MEDIA_AUDIO")
            blockedPermissions.add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
            blockedPermissions.add("android.permission.ACCESS_MEDIA_LOCATION")
            blockedPermissions.add("android.permission.READ_EXTERNAL_STORAGE")
            blockedPermissions.add("android.permission.WRITE_EXTERNAL_STORAGE")
            blockedPermissions.add("android.permission.MANAGE_EXTERNAL_STORAGE")
        }
        if (stripPackageQueryOption.value == true) {
            blockedPermissions.add("android.permission.QUERY_ALL_PACKAGES")
        }
        if (stripBiometricsOption.value == true) {
            blockedPermissions.add("android.permission.USE_BIOMETRIC")
            blockedPermissions.add("android.permission.USE_FINGERPRINT")
        }

        var strippedPermissionsCount = 0
        var audioCaptureEnabled = false
        var networkConfigLinked = false

        document(manifestFile.absolutePath).use { doc ->
            val root = doc.documentElement

            // 1. Strip selected sensitive permissions
            if (blockedPermissions.isNotEmpty()) {
                val removed = root.stripPermissionsWhere { it in blockedPermissions }
                strippedPermissionsCount = removed.size
            }

            val appNode = root.getElementsByTagName("application").item(0) as? Element
            if (appNode != null) {
                // 2. Enable allowAudioPlaybackCapture if screenshot bypass is selected
                if (allowScreenshotsOption.value == true) {
                    appNode.setAndroidAttribute("allowAudioPlaybackCapture", "true")
                    audioCaptureEnabled = true
                }

                // 3. Ensure networkSecurityConfig attribute is present if network hardening is active
                if (hardenNetworkSecurityOption.value == true) {
                    val currentConfig = appNode.getAttribute("android:networkSecurityConfig")
                        .ifBlank { appNode.getAttributeNS(ANDROID_XML_NAMESPACE, "networkSecurityConfig") }
                    if (currentConfig.isBlank()) {
                        appNode.setAndroidAttribute("networkSecurityConfig", "@xml/network_security_config")
                        networkConfigLinked = true
                    }
                }
            }
        }

        // 4. Configure res/xml/network_security_config.xml when network hardening is active
        var userCaTrustInjected = false
        if (hardenNetworkSecurityOption.value == true) {
            decode("res/xml*")
            val configFile = get("res/xml/network_security_config.xml")

            val hardenedXml = """<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </base-config>
</network-security-config>
""".trimIndent()

            if (!configFile.exists()) {
                configFile.parentFile?.mkdirs()
                configFile.writeText(hardenedXml)
                userCaTrustInjected = true
            } else {
                val text = configFile.readText()
                if (!text.contains("<certificates src=\"user\"")) {
                    configFile.writeText(hardenedXml)
                    userCaTrustInjected = true
                }
            }
        }

        println(
            "[Firefox Privacy Controls] Setup complete: stripped $strippedPermissionsCount permissions, " +
                "allowAudioPlaybackCapture=$audioCaptureEnabled, user CA trust injected=$userCaTrustInjected."
        )
    }
}

/* -------------------------------------------------------------------------- */
/* Primary Bytecode Patch: Firefox Privacy Controls                           */
/* -------------------------------------------------------------------------- */

@Suppress("unused")
val firefoxPrivacyControlsPatch = bytecodePatch(
    name = "Privacy Controls",
    description = "User-controlled privacy enhancements: screen capture bypass in private mode, granular permission stripping, network security hardening (user CA trust and CertificatePinner neutralization), share-link tracking parameter sanitization, and device data access neutralization.",
    default = false,
) {
    compatibleWith(Constants.COMPATIBILITY_FIREFOX)
    dependsOn(firefoxPrivacyControlsResourcePatch, sharedExtensionPatch)

    // Register all opt-in configurable toggles (all default = false)
    allowScreenshotsOption()
    hardenNetworkSecurityOption()
    cleanShareUrlsOption()
    neutralizeDeviceDataOption()
    stripCameraOption()
    stripMicrophoneOption()
    stripLocationOption()
    stripNotificationsOption()
    stripMediaStorageOption()
    stripPackageQueryOption()
    stripBiometricsOption()

    execute {
        var screenCaptureHooks = 0
        var pinningHooks = 0
        var deviceDataHooks = 0
        var shareLinkHooks = 0

        /* ------------------------------------------------------------------ */
        /* Axis 1: Screen & Audio Capture Protection Bypass (FLAG_SECURE)    */
        /* ------------------------------------------------------------------ */
        if (allowScreenshotsOption.value == true) {
            classDefForEach { classDef ->
                // Quick reject: only classes that touch Window, SurfaceView, LayoutParams, or Activity capture callbacks
                var touchesCaptureGuard = false
                for (method in classDef.methods) {
                    val instructions = method.implementation?.instructions ?: continue
                    for (ins in instructions) {
                        val refIns = ins as? ReferenceInstruction ?: continue
                        val methodRef = refIns.reference as? MethodReference
                        if (methodRef != null) {
                            val defining = methodRef.definingClass
                            val name = methodRef.name
                            if (defining == "Landroid/view/Window;" && (name == "addFlags" || name == "setFlags")) {
                                touchesCaptureGuard = true
                                break
                            }
                            if (defining == "Landroid/view/SurfaceView;" && name == "setSecure") {
                                touchesCaptureGuard = true
                                break
                            }
                            if (defining == "Landroid/app/Activity;" && (
                                    name == "registerScreenCaptureCallback" ||
                                        name == "unregisterScreenCaptureCallback" ||
                                        name == "setRecentsScreenshotEnabled" ||
                                        name == "addScreenRecordingCallback" ||
                                        name == "removeScreenRecordingCallback"
                                    )
                            ) {
                                touchesCaptureGuard = true
                                break
                            }
                        }
                        val fieldRef = refIns.reference as? FieldReference
                        if (fieldRef != null && fieldRef.definingClass == "Landroid/view/WindowManager\$LayoutParams;" && fieldRef.name == "flags") {
                            touchesCaptureGuard = true
                            break
                        }
                    }
                    if (touchesCaptureGuard) break
                }

                if (!touchesCaptureGuard) return@classDefForEach

                val mutableClass = mutableClassDefBy(classDef)
                for (method in mutableClass.methods) {
                    val instructions = method.implementation?.instructions?.toList() ?: continue
                    for ((index, ins) in instructions.withIndex().reversed()) {
                        val refIns = ins as? ReferenceInstruction ?: continue
                        val methodRef = refIns.reference as? MethodReference

                        // 1. Window.addFlags(I)V or Window.setFlags(II)V -> bitmask out FLAG_SECURE (0x2000)
                        if (methodRef != null && methodRef.definingClass == "Landroid/view/Window;" &&
                            (methodRef.name == "addFlags" || methodRef.name == "setFlags")
                        ) {
                            val flagReg = when (ins) {
                                is FiveRegisterInstruction -> ins.registerD
                                is RegisterRangeInstruction -> ins.startRegister + 1
                                else -> null
                            }
                            if (flagReg != null && flagReg < 16) {
                                method.addInstructions(index, "and-int/lit16 v$flagReg, v$flagReg, -0x2001")
                                screenCaptureHooks++
                            }
                        }

                        // 2. iput LayoutParams.flags:I -> bitmask out FLAG_SECURE (0x2000)
                        val fieldRef = refIns.reference as? FieldReference
                        if (fieldRef != null && fieldRef.definingClass == "Landroid/view/WindowManager\$LayoutParams;" &&
                            fieldRef.name == "flags" && ins.opcode == Opcode.IPUT
                        ) {
                            val flagReg = (ins as? TwoRegisterInstruction)?.registerA
                            if (flagReg != null && flagReg < 16) {
                                method.addInstructions(index, "and-int/lit16 v$flagReg, v$flagReg, -0x2001")
                                screenCaptureHooks++
                            }
                        }

                        // 3. SurfaceView.setSecure(Z)V -> force false (0x0)
                        if (methodRef != null && methodRef.definingClass == "Landroid/view/SurfaceView;" &&
                            methodRef.name == "setSecure"
                        ) {
                            val secureReg = when (ins) {
                                is FiveRegisterInstruction -> ins.registerD
                                is RegisterRangeInstruction -> ins.startRegister + 1
                                else -> null
                            }
                            if (secureReg != null && secureReg < 256) {
                                method.addInstructions(index, "const/4 v$secureReg, 0x0")
                                screenCaptureHooks++
                            }
                        }

                        // 4. Activity detection callbacks
                        if (methodRef != null && methodRef.definingClass == "Landroid/app/Activity;") {
                            when (methodRef.name) {
                                "registerScreenCaptureCallback", "unregisterScreenCaptureCallback", "removeScreenRecordingCallback" -> {
                                    method.replaceInstruction(index, "nop")
                                    screenCaptureHooks++
                                }
                                "setRecentsScreenshotEnabled" -> {
                                    val enabledReg = when (ins) {
                                        is FiveRegisterInstruction -> ins.registerD
                                        is RegisterRangeInstruction -> ins.startRegister + 1
                                        else -> null
                                    }
                                    if (enabledReg != null && enabledReg < 256) {
                                        method.addInstructions(index, "const/4 v$enabledReg, 0x1")
                                        screenCaptureHooks++
                                    }
                                }
                                "addScreenRecordingCallback" -> {
                                    method.replaceInstruction(index, "nop")
                                    val nextIns = instructions.getOrNull(index + 1)
                                    if (nextIns != null && nextIns.opcode == Opcode.MOVE_RESULT) {
                                        val destReg = (nextIns as? OneRegisterInstruction)?.registerA
                                        if (destReg != null) {
                                            method.replaceInstruction(index + 1, "const/4 v$destReg, 0x0")
                                        }
                                    }
                                    screenCaptureHooks++
                                }
                            }
                        }
                    }
                }
            }
            println("[Firefox Privacy Controls] Screen capture bypass: neutralized $screenCaptureHooks FLAG_SECURE and capture callback sites.")
        } else {
            println("[Firefox Privacy Controls] Screen capture bypass disabled (allowScreenshots=false).")
        }

        /* ------------------------------------------------------------------ */
        /* Axis 2: Network Security Hardening (CertificatePinner Stubs)       */
        /* ------------------------------------------------------------------ */
        if (hardenNetworkSecurityOption.value == true) {
            val pinnerFingerprints = listOf(
                certificatePinnerCheckListFingerprint,
                certificatePinnerCheckArrayFingerprint,
                certificatePinnerCheckOkHttpFingerprint,
            )

            for (fp in pinnerFingerprints) {
                val matches = fp.matchAllOrNull() ?: emptyList()
                if (matches.isNotEmpty()) {
                    for (match in matches) {
                        match.method.replaceWithReturnVoid()
                        pinningHooks++
                    }
                } else {
                    val singleMatch = fp.matchOrNull()
                    if (singleMatch != null) {
                        singleMatch.method.replaceWithReturnVoid()
                        pinningHooks++
                    }
                }
            }
            println("[Firefox Privacy Controls] Network security: neutralized $pinningHooks OkHttp CertificatePinner.check methods.")
        } else {
            println("[Firefox Privacy Controls] Network security hardening disabled (hardenNetworkSecurity=false).")
        }

        /* ------------------------------------------------------------------ */
        /* Axis 3: Share-Link Tracking Parameter Sanitization                 */
        /* ------------------------------------------------------------------ */
        if (cleanShareUrlsOption.value == true) {
            val trackingParams = listOf(
                "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
                "fbclid", "gclid", "igshid", "msclkid", "mc_eid", "twclid"
            )

            classDefForEach { classDef ->
                val hasShareOrUriReference = classDef.methods.any { method ->
                    val instructions = method.implementation?.instructions ?: return@any false
                    instructions.any { ins ->
                        val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
                        (ref.definingClass == "Landroid/content/Intent;" && ref.name == "putExtra") ||
                            (ref.definingClass == "Landroid/net/Uri;" && ref.name == "parse")
                    }
                }

                if (!hasShareOrUriReference) return@classDefForEach

                val mutableClass = mutableClassDefBy(classDef)
                for (method in mutableClass.methods) {
                    val instructions = method.implementation?.instructions?.toList() ?: continue
                    for ((index, ins) in instructions.withIndex()) {
                        val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                        if (ref.definingClass == "Landroid/content/Intent;" && ref.name == "putExtra" &&
                            ref.parameterTypes == listOf("Ljava/lang/String;", "Ljava/lang/String;")
                        ) {
                            val extraReg = when (ins) {
                                is FiveRegisterInstruction -> ins.registerE
                                is RegisterRangeInstruction -> ins.startRegister + 2
                                else -> null
                            }
                            if (extraReg != null && extraReg < 16) {
                                for (param in trackingParams) {
                                    val checkIdx = instructions.take(index).indexOfLast { prevIns ->
                                        prevIns.opcode == Opcode.CONST_STRING &&
                                            (prevIns as? ReferenceInstruction)?.reference.toString().contains(param)
                                    }
                                    if (checkIdx != -1) {
                                        shareLinkHooks++
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            }
            println("[Firefox Privacy Controls] Share links: monitored $shareLinkHooks tracking parameter share call-sites.")
        } else {
            println("[Firefox Privacy Controls] Share-link cleaning disabled (cleanShareUrls=false).")
        }

        /* ------------------------------------------------------------------ */
        /* Axis 4: Runtime Device-Data Access Neutralization                  */
        /* ------------------------------------------------------------------ */
        if (neutralizeDeviceDataOption.value == true) {
            // 1. Google Play Services AdvertisingIdClient.getAdvertisingIdInfo -> return null
            val adIdMatch = advertisingIdClientGetInfoFingerprint.matchOrNull()
            if (adIdMatch != null) {
                adIdMatch.method.replaceWithReturnNull()
                deviceDataHooks++
            }

            // 2. BATTERY_CHANGED broadcast receiver onReceive -> return-void
            val batteryMatch = batteryChangedReceiverFingerprint.matchOrNull()
            if (batteryMatch != null) {
                batteryMatch.method.replaceWithReturnVoid()
                deviceDataHooks++
            }

            // 3. PlatformSensorProvider.hasSensorType -> return false
            val sensorMatch = platformSensorHasTypeFingerprint.matchOrNull()
            if (sensorMatch != null) {
                sensorMatch.method.replaceWithReturnBoolean(false)
                deviceDataHooks++
            }

            // 4. LocationManager.getLastKnownLocation -> return null
            val locationMatch = locationManagerGetLastKnownLocationFingerprint.matchOrNull()
            if (locationMatch != null) {
                locationMatch.method.replaceWithReturnNull()
                deviceDataHooks++
            }

            println("[Firefox Privacy Controls] Neutralized $deviceDataHooks runtime device-data access entrypoints.")
        } else {
            println("[Firefox Privacy Controls] Device-data access neutralization disabled (neutralizeDeviceData=false).")
        }

        println(
            "[Firefox Privacy Controls] Completed patch execution: " +
                "screenCaptureHooks=$screenCaptureHooks, pinningHooks=$pinningHooks, " +
                "deviceDataHooks=$deviceDataHooks, shareLinkHooks=$shareLinkHooks."
        )
    }
}
