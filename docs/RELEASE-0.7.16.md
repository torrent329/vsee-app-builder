# Vsee App Builder 0.7.16

## What's new

- Adds reviewed compatibility data for original Heat Live 5.9.6, Live Ultra 5.8.1, and Live Ultra+ 5.8.1 on the V6 Plus.
- Keeps the existing six-app selection flow. Owners can select only the compatible apps they want to make.
- Keeps the existing first-run notice and temporary access flow. This update does not use Android's full-backup prompt.

The new versions are delivered through the signed compatibility catalog. **Check compatibility** needs an Internet connection to fetch the catalog and the matching neutral templates once. Previously built-in versions remain available offline.

## Verification

The three original APKs were copied from the owner's V6 Plus and matched by exact package, version, size, and SHA-256. Each neutral template passed ZIP integrity, placeholder, native-library pin, entry-count, and catalog-signature checks. The catalog is signed by the key already trusted by builder 0.7.15.

On-device export and playback results will be recorded here after the V6 Plus test. Generated owner APKs contain information tied to the owner's box and must stay private.
