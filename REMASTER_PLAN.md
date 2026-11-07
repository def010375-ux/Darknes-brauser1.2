# Darknes Tor 2.0 Remaster

## Product direction

A maximum-scope redesign of Darknes Tor built on the existing Android application, not a replacement project. Preserve the current GeckoView browser engine and the working user-facing features while moving toward modular architecture and a cohesive obsidian / glass / cyan-violet visual system.

## Non-negotiable compatibility rules

- Preserve the existing Supabase project `Darknes-ID` in West EU (Ireland).
- Keep the current `SUPABASE_URL` and `SUPABASE_PUBLISHABLE_KEY` BuildConfig wiring unchanged.
- Do not replace the Supabase project, auth endpoints, database, user IDs, or existing account records.
- Preserve email registration, password login, refresh-token session renewal, encrypted Android Keystore session storage, logout, and the optional continue-without-account path.
- Never add a Supabase secret/service-role key, database password, or other server secret to the Android app, Git history, or APK.
- Keep Row Level Security (RLS) as the enforcement boundary for user-owned cloud data. Never trust client-side user IDs as authorization.
- Any future cloud sync must be additive, use the existing authenticated user/session, and be introduced with backward-compatible schema changes.
- Do not claim Tor anonymity or route traffic through Tor unless a real Tor service/proxy is implemented and verified. GeckoView alone is not proof of Tor routing.

## Current baseline inspected

- Android application module: `:app`.
- UI: Kotlin + Jetpack Compose / Material 3.
- Browser engine: Mozilla GeckoView.
- Existing features include tabs and tab groups, private browsing, bookmarks, history, downloads, offline text saves, theme/wallpaper customization, reader mode, privacy controls, and a GitHub Releases tester panel.
- Darknes ID registration/login is implemented in `DarknesIdPanel.kt`; session persistence/refresh/revoke is implemented in `DarknesSessionStore.kt`.
- Existing app version before this branch: `1.5-beta`, versionCode `6`.

## Remaster workstreams

### 1. Foundation and architecture
- Establish feature boundaries for browser shell, tabs, home, downloads, privacy, account, settings, reader, and AI.
- Move persistence and network operations out of composables into repositories/use-cases.
- Add UI state models and lifecycle-safe coroutine handling.
- Add automated tests for session compatibility, tab persistence, and private-mode isolation.

### 2. Visual system
- Obsidian-black surfaces, layered glass panels, restrained cyan/violet accents, consistent spacing and typography.
- Shared design tokens, reusable cards, buttons, fields, navigation, dialogs, sheets, and motion.
- Responsive layouts for small Android phones and larger screens.
- Respect reduced-motion and battery/performance settings.

### 3. Browser experience
- Rebuild the browser shell, address/search bar, navigation controls, home dashboard, tab overview, and tab animations.
- Preserve existing GeckoView sessions and improve tab lifecycle, restore behavior, and group management.
- Keep private tabs isolated from normal history/session snapshots.
- Improve downloads, bookmarks, history, offline reader, and permissions.

### 4. Privacy and security
- Central security dashboard with understandable tracker/content-blocking status.
- Per-site permissions and clear security indicators.
- Harden external intents, downloads, file access, and WebView/GeckoView bridges.
- Explain limitations honestly; no fake VPN, Tor, anonymity, or malware-scanning claims.

### 5. Darknes ecosystem
- Keep existing Darknes ID sign-in and registration.
- Add profile/device management improvements without changing identity provider.
- Design cloud sync for bookmarks, settings, and workspaces as an additive feature with RLS-protected tables and migrations.
- Keep local-only use available.

### 6. Exclusive features
- Darknes Studio: themes, wallpapers, blur, motion, layout presets, import/export.
- Darknes Workspace: named tab collections and restorable sessions.
- Darknes AI: initially a clearly labeled integration; do not embed private page content in third-party requests without explicit user action.
- Black Hole 3D: seamless animation with quality controls and low-power fallback.
- Download Manager: progress, queue/status, safe file handling, and history.

### 7. Quality and release
- Build debug APK on each implementation milestone.
- Run unit/UI tests and static checks where available.
- Verify fresh install, upgrade from 1.5 data, account sign-in, account sign-out, and use without account.
- Verify existing Supabase registration/login/refresh flows before and after auth-adjacent changes.
- Publish a GitHub Release only after a verified APK asset is attached. Until then, call 2.0 Remaster an in-development branch, not a released version.

## Delivery order

1. Foundation, version metadata, and regression baseline.
2. New design system and browser shell.
3. Tabs, home, navigation, and motion.
4. Privacy/security center and site controls.
5. Downloads, bookmarks, history, reader, and workspace.
6. Darknes Studio and Black Hole 3D polish.
7. Darknes ID profile and additive cloud sync.
8. AI integration with explicit privacy controls.
9. Performance, accessibility, migration, tests, and release verification.

## Acceptance gate for every milestone

- No regression in Darknes ID registration/login/session refresh/logout.
- No destructive database or auth-provider changes.
- No secrets added to source or APK.
- No private browsing data written into normal history/session storage.
- App compiles and the relevant tests pass before the milestone is called complete.
