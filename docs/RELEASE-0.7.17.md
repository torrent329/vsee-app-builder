# Vsee App Builder 0.7.17

## What's new

- Fixes `[ADB_EMBEDDED_STATUS_FAILED]` when the V6 already has a separate root ADB session open.
- Verifies that the existing local ADB listener provides a root shell, then uses it without stopping or restarting ADB. When ADB is off, the builder retains its temporary access startup path.
- Uses signed catalog revision 3 for original Heat Live 6.0.0 and HeatVod 6.9.9. The catalog also remains available to older builder versions that support signed updates.

## Verification

Version 0.7.17 installed over 0.7.16 on a V6 Plus. With root ADB already open, **Check compatibility** matched all six installed original apps. **Make APKs** saved one selected Heat Live APK, then a second run saved all six selected APKs in Downloads. The ADB connection remained open throughout both exports. The integrated installer passed Android build, alignment, signature, and embedded relay checks. Separate-device playback was not checked for these builds.

Generated owner APKs contain device-specific information. Keep them private.
