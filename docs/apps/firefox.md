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

---

## Behavioral Hazards & Warnings

1. **Client-Side vs Server-Side Entitlements & Sync**:
   Modifications applied via Dalvik bytecode transformations and manifest adjustments operate purely on the client side. Any server-side services (e.g. Mozilla Accounts sync, server-enforced remote configuration, upstream extension store verification) remain authoritative on remote infrastructure and cannot be altered or bypassed by client-side patching.

2. **Loss of Upstream Crash Reporting**:
   Neutralizing Mozilla crash reporter components (`CrashReporter`, `CrashHandlerService`, `SendCrashReportService`, `SendCrashTelemetryService`) prevents Socorro and Mozilla crash dashboard submissions. Uncaught native or JVM crashes will terminate cleanly without transmitting telemetry, stack traces, or minidumps to Mozilla servers.

3. **Preservation of Functional Browser Endpoints**:
   Telemetry blocking strictly preserves core operational endpoints, specifically Mozilla Safe Browsing (`shavar` list updates, phishing/malware protection tables), Mozilla Add-on / extension directory lookups, search engine suggestion endpoints, and captive portal detection. Only tracking, attribution, and analytics domains are redirected or blocked.

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
