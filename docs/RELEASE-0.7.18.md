# Vsee App Builder 0.7.18

## What changed

- Added a signed compatibility entry and neutral template for the original Heat Live 6.0.2 APK found on a V6 Plus.
- **Check compatibility** now checks for a signed catalog update every time it is selected. A newly reviewed app version no longer has to wait for the previous four-hour refresh interval.
- Existing supported versions and app selection behavior remain available.

## Verification

- The original Heat Live 6.0.2 package, publisher signature, ZIP payload, and version were checked before adding the entry.
- The new catalog signature verifies against the public key built into the builder. The neutral template has no owner profile, and its pinned native placeholders match the catalog.
- On the V6 Plus, the builder matched all six installed originals and exported Heat Live 6.0.2 alone to Downloads while ADB stayed open. The exported APK passed Android signature verification and contained the selected owner's profile. It reached the main activity and showed the same first-run data-loading screen as the original Heat Live 6.0.2 app; neither completed loading during this check.

Playback on a separate Android TV device has **not** been verified for this update. A viewer reported that apps generated from earlier live-app versions stayed on “Receiving Data” on a Google TV Streamer 4K. This release does not establish a fix for that report.
