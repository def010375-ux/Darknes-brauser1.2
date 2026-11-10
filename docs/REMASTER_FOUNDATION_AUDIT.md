# Darknes Tor 2.0 Remaster — Foundation Audit

Audit baseline: `remaster/2.0` at `f034a5e843168703e0aae5e054e64c759ac6bc40` (2026-11-07).
This document records source-level observations only. It does not certify a successful build or runtime behavior.

## Confirmed Android configuration

- Android application module: `:app`.
- Namespace and application ID: `com.darknes.tor`.
- Kotlin + Jetpack Compose + Material 3.
- Browser engine dependency: Mozilla GeckoView `159.0.20261022150525`.
- Java source/target compatibility: 17.
- `compileSdk = 37`, `compileSdkMinor = 2`, `targetSdk = 36`, `minSdk = 26`.
- App metadata is already `versionName = "2.0-remaster"`, `versionCode = 7`.
- AGP plugin: `9.3.1`; Kotlin Compose plugin: `2.4.21`.
- Gradle wrapper files are absent from the inspected tree; CI installs Gradle directly.

## Existing structure and compatibility boundaries

- The active browser implementation is `app/src/main/java/com/darknes/tor/MainActivity.kt` (about 287 KB at audit time).
- Shared visual tokens already exist in `DarknesDesignTokens.kt`.
- Darknes ID UI is in `DarknesIdPanel.kt`; encrypted session persistence and refresh/revoke logic are in `DarknesSessionStore.kt`.
- Device registration UI/API is in `DarknesDevices.kt`.
- Keep the existing Supabase URL and publishable-key BuildConfig wiring unchanged.
- Preserve existing account IDs, auth endpoints, encrypted session format, device registry, and RLS policies.
- The root-level `MainActivity.kt` and root `AndroidManifest.xml` are separate legacy/prototype files; the Android module uses files under `app/src/main`. Do not edit the root copies as if they were active app sources.
- `DarknesIdClient.kt` contains a legacy auth panel/token-store implementation. The active MainActivity references `DarknesIdPanel`; do not remove legacy code until all references and migration implications have been checked.

## Build and test observations

- `.github/workflows/android-apk.yml` is manual-dispatch only and builds `:app:assembleDebug`.
- A second workflow exists at `.github/workflows/build-apk.yml`; it targets `main`/`master` on push and has a different SDK/Gradle setup.
- No `app/src/test`, `app/src/androidTest`, Gradle wrapper, or version catalog appeared in the inspected repository tree.
- No APK build, test task, emulator run, or workflow dispatch was performed during this audit, in accordance with the project instruction to wait for explicit approval before building/running APKs.

## Findings and safe next steps

1. The browser's main source file is too large to safely refactor in one pass. Extract cohesive features incrementally, keeping public behavior and state ownership stable.
2. Establish unit-test infrastructure before moving authentication, persistence, tab-state, or privacy logic.
3. Consolidate CI only after confirming the intended SDK/Gradle setup; do not trigger either APK workflow without approval.
4. Review the duplicate legacy authentication implementation and remove it only after proving it is unused and not part of a migration path.
5. Continue to describe the product as a GeckoView-based browser; do not claim Tor routing/anonymity without a real, verified Tor service/proxy.
6. Keep the user-approved visual direction: dark surfaces, white crown with violet backlight; do not restore the black-hole centerpiece.

## Change made on the foundation branch

`DarknesSessionStore.kt` no longer deletes the locally encrypted session merely because a refresh request returns a non-2xx response. A failed HTTP response can be transient; the app now returns a failed refresh result while retaining the stored session for a retry. Explicit logout remains responsible for clearing local credentials.

This is a narrowly scoped resilience fix. It has not been build- or runtime-tested yet.
