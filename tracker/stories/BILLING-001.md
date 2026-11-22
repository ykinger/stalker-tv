# BILLING-001 — VibeTV Pro: unlock extra profiles and playlists

**Status:** Backlog — blocked on Google Play Console products
**Priority:** Next milestone (pre-release)
**Dependencies:** Play Console subscription + one-time product created and activated;
license testers and an internal testing track available for purchase verification.

## Goal

Let one free viewer profile and one free playlist stay available to everyone, and
gate adding more behind a VibeTV Pro entitlement. Pro is granted when the device's
Google Play account has either an active annual subscription **or** a completed
one-time purchase. This is pre-release work: there are no existing customers to
migrate or grandfather, so limits can be enforced directly when billing ships.

## Current state of the app

As of commit `a5e4a96`:

- Android TV app in Kotlin / Compose for TV, Media3 player, OkHttp portal client,
  minSdk 26 / targetSdk 35, application ID `com.ykinger.stalkertv`.
- VibeTV branding is complete: adaptive launcher icon, TV banner, full/horizontal/
  icon-only logos in the left menu, profile chooser, landing screen and settings.
  User-facing name is VibeTV; corporation is Vio Technologies.
- Onboarding: Terms gate, viewer profile creation and switching, playlist setup
  (Stalker Portal only) with host, editable MAC and optional serial ID.
- Connection: portal handshake + authenticated profile validation, category scan,
  encrypted local playlist storage via Android Keystore; session tokens stay in memory.
- Browsing: left menu with Search / TV / Movies / Shows / Recordings / My list /
  Settings, channel guide with EPG, VOD categories with All-category paging,
  search, sort, details, seasons and per-episode playback.
- Verification already passing:
  - `:core:test`, `:app:testDebugUnitTest`, `:app:testReleaseUnitTest`
  - `:app:assembleDebug`, `:app:assembleRelease` with JDK 17, offline Gradle
  - `git diff --check`
  - `ConnectedTvFocusTest`: 13/13 on `Television_4K(AVD) - 16` (960×540dp)
  - APK badging: label `VibeTV`, icon `@mipmap/ic_launcher`, banner `tv_banner.png`
  - Expanded left-menu bounds measured on the 4K emulator; all items fit after
    switching the expanded logo to the horizontal lockup.
- Not yet done: physical-box and real-provider validation, Play Console listing,
  signed release upload, and any monetisation.

## Proposed Play products

| Product ID | Type | Notes |
| --- | --- | --- |
| `vibetv_pro_annual` | Auto-renewing annual subscription | Base plan created in Play Console |
| `vibetv_pro_lifetime` | One-time product, non-consumable | One-time Pro unlock |

Prices and billing period text come from Play's `ProductDetails`; do not hardcode
prices in the UI. Products must be acknowledged and never consumed.

## Scope

| Ticket | Scope | Status |
| --- | --- | --- |
| BILLING-001-01 | Add Play Billing dependency and a testable `ProBilling` interface + state model | Backlog |
| BILLING-001-02 | Implement `PlayProBilling`: connect/reconnect, query products and purchases, purchase, acknowledge, restore | Backlog — needs Play Console |
| BILLING-001-03 | Cache entitlement locally with a timestamp so Pro survives app restarts and short offline periods | Backlog |
| BILLING-001-04 | Free/Pro limit rules: 1 profile + 1 playlist free; Pro unlocks more | Backlog |
| BILLING-001-05 | TV paywall screen with annual and lifetime choices, restore, manage subscription, and billing-unavailable states | Backlog |
| BILLING-001-06 | Gate Add profile / Create and Add playlist / save; resume the original action after purchase | Backlog |
| BILLING-001-07 | Pro status, Restore purchase and Manage subscription in Settings | Backlog |
| BILLING-001-08 | Unit tests, Compose tests with a fake billing implementation, and Play internal-testing verification | Backlog — needs license testers |

## Acceptance criteria

- A fresh install can create exactly one viewer profile and one playlist without
  Play Console products being configured.
- Once one profile exists, Add profile opens the Pro paywall instead of the
  creation form. Once one playlist exists, Add playlist / Add another playlist
  opens the paywall. The save path enforces the same rule.
- The paywall shows Play-provided prices for the annual subscription and the
  one-time unlock, and works with D-pad / OK / Back only.
- An active annual subscription **or** an acknowledged one-time purchase grants
  Pro. A pending purchase does not grant Pro.
- After a successful purchase, the user returns to the action that opened the
  paywall: profile creation or playlist setup.
- Restore purchase re-queries Play and updates Pro state. Manage subscription
  opens Google Play's subscription management.
- Purchases are acknowledged within the Play grace period and are not consumed.
- Billing being unavailable (sideloaded build, no Play Store, signed-out account)
  does not break existing app functionality; the paywall explains the problem.
- No purchase tokens, account identifiers, or secrets are logged or written into
  tracker documentation.

## Verification notes

- JVM tests cover entitlement resolution and the free/Pro limit functions.
- Compose tests inject a fake `ProBilling` to verify gating, purchase continuation
  and restore without contacting Google Play.
- Real purchases must be tested from an internal testing track with license
  testers. A sideloaded debug APK cannot complete a Play purchase, even though
  the TV emulator has Google Play installed.
- Test annual purchase, one-time purchase, cancel, refund, restore, pending
  purchase, offline restart and a second Google account on the TV.

## Out of scope

- No backend or server-side receipt validation in this slice; entitlement is
  determined on-device from Play Billing with a local cache. A backend can be
  added later if tamper resistance becomes a requirement.
- No additional Pro features beyond extra profiles and playlists.
- No migration or grandfathering: the app is still in development.
- Physical-device and real-provider compatibility remain separate release work.
