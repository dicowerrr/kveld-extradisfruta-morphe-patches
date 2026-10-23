# Firefox Patches Guide

Technical documentation and patch catalog for Firefox on Android.

---

## Target & Compatibility

| Property | Value |
| :--- | :--- |
| **Target Application** | Firefox |
| **Package Name** | `org.mozilla.firefox` |
| **Supported Target Version** | `158.0.2` |
| **Target File Format** | standalone APK |
| **Recommended Architecture** | `arm64-v8a` (nodpi, versionCode 2016191575) |
| **Official Download Source** | [APKMirror](https://www.apkmirror.com/apk/mozilla/firefox/) |

---

## Applied Patches Catalog

| Patch Name | Typology | Default State | Dependencies | Description |
| :--- | :--- | :--- | :--- | :--- |
| **Block Telemetry & Trackers** | `bytecodePatch` | `true` (Enabled) | `Firefox Telemetry Manifest Purge` | Neutralizes Adjust attribution, Mozilla Glean telemetry, crash reporting dispatchers, and Google DataTransport schedulers. Includes `custom-blocked-hosts` stringOption. Core bytecode interception is always-on. |
| **Firefox Telemetry Manifest Purge** | `resourcePatch` | `false` (Opt-in) | None | Strips advertising/tracking permissions (`AD_ID`, AdServices, InstallReferrer), disables Adjust and crash reporting services/receivers, and injects opt-out metadata flags in AndroidManifest.xml. |
| **Bypass Compatibility Checks** | `bytecodePatch` | `false` (Opt-in) | `Firefox Compatibility Manifest Normalization` | Neutralizes client-side environment checks (`isRooted`, `isEmulator`, `isDebuggerAttached` forced to false) in environment utility classes while preserving network, deep links, and GeckoView assets. |
| **Firefox Compatibility Manifest Normalization** | `resourcePatch` | `false` (Opt-in) | None | Strips `sharedUserId` and `sharedUserLabel` from the root manifest element when present to eliminate installation conflicts. |
| **Privacy Controls** | `bytecodePatch` | `false` (Opt-in) | `Privacy Controls Setup` | Provides opt-in privacy toggles: screenshot enablement in private tabs (`allowScreenshots`), network security hardening (`hardenNetworkSecurity`), URL tracking parameter stripping (`cleanShareUrls`), and runtime device identifier neutralization (`neutralizeDeviceData`). |
| **Privacy Controls Setup** | `resourcePatch` | `false` (Opt-in) | None | Provides optional manifest-level permission stripping across 7 granular privacy axes (camera, microphone, location, notifications, media-storage, package-query, biometrics) and injects network security configuration for user CA trust. |
| **Firefox Debloat** | `resourcePatch` | `false` (Opt-in) | None | Provides footprint reduction options: prunes built-in WebExtension asset bundles (`pruneExtensionBundles`), disables GeckoView background profiler service (`disableProfilerService`), and stubs onboarding tutorial MP4 video assets with minimal 32-byte headers (`stubOnboardingMedia`). |

---

## Behavioral Hazards & Warnings

1. **Client-Side vs Server-Side Entitlements & Sync**:
   Modifications applied via Dalvik bytecode transformations and manifest adjustments operate purely on the client side. Any server-side services (e.g. Mozilla Accounts sync, server-enforced remote configuration, upstream extension store verification) remain authoritative on remote infrastructure and cannot be altered or bypassed by client-side patching.

2. **Loss of Upstream Crash Reporting**:
   Neutralizing Mozilla crash reporter components (`CrashReporter`, `CrashHandlerService`, `SendCrashReportService`, `SendCrashTelemetryService`) prevents Socorro and Mozilla crash dashboard submissions. Uncaught native or JVM crashes will terminate cleanly without transmitting telemetry, stack traces, or minidumps to Mozilla servers.

3. **Preservation of Functional Browser Endpoints**:
   Telemetry blocking strictly preserves core operational endpoints, specifically Mozilla Safe Browsing (`shavar` list updates, phishing/malware protection tables), Mozilla Add-on / extension directory lookups, search engine suggestion endpoints, and captive portal detection. Only tracking, attribution, and analytics domains are redirected or blocked.

4. **Opt-in Compatibility Gate**:
   Environment neutralization and manifest normalization hooks remain disabled by default (`false`) until specifically required and verified on target devices with modified runtimes, emulators, or conflicting shared UID installations.

5. **Granular Permission Stripping Hazards**:
   The permission stripping options in Privacy Controls completely remove respective `<uses-permission>` tags from AndroidManifest.xml. Enabling `stripCamera` or `stripMicrophone` disables WebRTC video/audio conferencing and QR code scanning; enabling `stripLocation` disables web geolocation APIs; enabling `stripNotifications` suppresses download status, media playback controls, and background tab notifications.

6. **Network Security Hardening Scope**:
   The `hardenNetworkSecurity` option enables trust for user-added CA certificates and neutralizes certificate pinning checks. This should only be activated for legitimate local traffic inspection, debugging, or custom proxy setups, as trusting user CAs reduces defense-in-depth against untrusted local networks.

7. **Preinstalled Extension Bundles Pruning**:
   Enabling `pruneExtensionBundles` removes bundled WebExtension XPI/asset archives under `assets/extensions/`. While this achieves significant byte savings, any built-in browser helper add-ons packaged directly with the APK will be absent. Users can reinstall required add-ons manually via Mozilla Add-ons (AMO).

8. **Profiler Service Disablement**:
   Enabling `disableProfilerService` disables the GeckoView profiler recording and sampling background service. While saving CPU cycles and memory overhead, upstream Mozilla performance diagnostic captures via `about:profiling` will be unavailable.

---

## Deep Technical Patch Breakdown

### 1. Block Telemetry & Trackers Layer

- **Typology**: `bytecodePatch` (`Block Telemetry & Trackers`, default `true`) + `resourcePatch` (`Firefox Telemetry Manifest Purge`, default `false` opt-in).
- **Options**: `custom-blocked-hosts` (`stringOption`, comma-separated custom domain blocklist).
- **Adjust Attribution Neutralization**:
  - Manifest layer removes `com.adjust.sdk.AdjustReferrerReceiver` and disables broadcast receivers handling install referrer callbacks.
  - Bytecode layer stubs `PackageHandler` and `ActivityHandler` event queues, short-circuiting attribution dispatches and session tracking calls.
  - Intercepts and nullifies connections destined for `app.adjust.com`, `app.adjust.net`, and `app.adjust.world`.
- **Mozilla Glean Telemetry Neutralization**:
  - Forces Glean configuration query `isUploadEnabled` to return `false` unconditionally.
  - Injects immediate early returns into `submitPing`, `PingType.record`, and `HttpUploader` transmission pipelines.
  - Blocks telemetry collection hosts, including `incoming.telemetry.mozilla.org`.
- **Crash Reporting Neutralization**:
  - Manifest layer disables `CrashHandlerService`, `SendCrashReportService`, and `SendCrashTelemetryService`.
  - Bytecode hooks stub `CrashReporter` initialization and submission routines to discard crash dumps.
  - Suppresses outgoing network traffic targeting `crash-reports.mozilla.com`.
- **Google DataTransport & Ad Services**:
  - Strips `com.google.android.gms.permission.AD_ID`, AdServices permissions, and Play Store `INSTALL_REFERRER`.
  - Disables Google DataTransport background upload alarms and jobs.
  - Injects opt-out manifest metadata flags to suppress runtime SDK auto-initialization.

### 2. Bypass Compatibility Checks Layer

- **Typology**: `bytecodePatch` (`Bypass Compatibility Checks`, default `false`) + `resourcePatch` (`Firefox Compatibility Manifest Normalization`, default `false`).
- **Manifest Normalization**:
  - Detects and removes `android:sharedUserId` and `android:sharedUserLabel` attributes from `<manifest>` if present, preventing signature/UID conflicts during installation.
- **Client-Side Environment Neutralization**:
  - Targets client-side integrity and environment verification utilities within application packages.
  - Bypasses root detection routines (`isRooted` forced to return `false`).
  - Bypasses emulator detection routines (`isEmulator` forced to return `false`).
  - Bypasses debugging checks (`isDebuggerAttached` forced to return `false`).
- **Preserved Operational Invariants**:
  - Retains standard `INTERNET` access, system hardware feature declarations, and Android deep link `VIEW` intent filters.
  - Preserves GeckoView initialization routines, native shared library bindings, and `omni.ja` archive extraction paths.
  - Remains disabled by default until verified against specific device deployment requirements.

### 3. Privacy Controls Layer

- **Typology**: `bytecodePatch` (`Privacy Controls`, default `false`) + `resourcePatch` (`Privacy Controls Setup`, default `false`).
- **Screenshot Protection Bypass (`allowScreenshots`)**:
  - Overrides window flag management to prevent setting `WindowManager.LayoutParams.FLAG_SECURE` in private browsing tabs and secure UI surfaces, enabling user screenshots and display mirroring.
- **Network Security Hardening (`hardenNetworkSecurity`)**:
  - Overrides OkHttp and GeckoView certificate pinning verifiers to accept configured trust stores.
  - Injects an updated XML Network Security Configuration trusting user-installed CA certificates (`<certificates src="user" />`).
- **URL Parameter Stripping (`cleanShareUrls`)**:
  - Intercepts link sharing actions and outgoing URL dispatchers.
  - Sanitizes web addresses by pruning common marketing and tracking parameters, including `utm_source`, `utm_medium`, `utm_campaign`, `utm_term`, `utm_content`, `fbclid`, `gclid`, and `igshid`.
- **Device Identifier Neutralization (`neutralizeDeviceData`)**:
  - Forces advertising identifier queries (`AdvertisingIdClient`) to return null or zeroed strings (`00000000-0000-0000-0000-000000000000`).
  - Stubs sensor listener and battery status telemetry queries to prevent passive device fingerprinting.
- **Granular Permission Stripping (7 Axes)**:
  - `stripCamera`: Removes `android.permission.CAMERA`.
  - `stripMicrophone`: Removes `android.permission.RECORD_AUDIO`.
  - `stripLocation`: Removes `android.permission.ACCESS_FINE_LOCATION` and `android.permission.ACCESS_COARSE_LOCATION`.
  - `stripNotifications`: Removes `android.permission.POST_NOTIFICATIONS`.
  - `stripMediaStorage`: Removes external storage and media permissions (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`, `READ_EXTERNAL_STORAGE`).
  - `stripPackageQuery`: Removes `QUERY_ALL_PACKAGES` and broad `<queries>` package visibility elements.
  - `stripBiometrics`: Removes `USE_BIOMETRIC` and `USE_FINGERPRINT`.
- **Vetoed Architectural Scope**:
  - Offline Mode is explicitly **VETOED** and excluded; as a web browser, Firefox requires continuous network communication to perform core navigation. Offline toggles are architecturally inapplicable.

### 4. Debloat Layer

- **Typology**: `resourcePatch` (`Firefox Debloat`, default `false` opt-in).
- **WebExtension Bundles Pruning (`pruneExtensionBundles`)**:
  - Prunes pre-packaged WebExtension assets stored under `assets/extensions/` within the APK archive.
  - Generates measurable package footprint savings while preserving runtime extension installation support from external sources (addons.mozilla.org).
- **GeckoView Profiler Service Disablement (`disableProfilerService`)**:
  - Disables manifest registration for Gecko profiler components and services in `AndroidManifest.xml`.
  - Suppresses background profiler polling and thread sampling allocation overhead.
- **Onboarding Tutorial Media Stubbing (`stubOnboardingMedia`)**:
  - Replaces non-essential first-run onboarding MP4 video assets under `res/raw/` with a minimal 32-byte deterministic MP4 header stub.
  - Neutralizes onboarding video bloat without triggering resource resolution crashes or XML layout inflation failures.
