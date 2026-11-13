# Vsee App Builder

**Vsee App Builder** is an Android TV app by TORR3NT's Tech. On a compatible V6 box, it checks the installed original apps and makes APKs in a dated folder in Downloads. The owner can choose which compatible apps to build, then copy those files to another Android TV device.

The screen has two actions: **Check compatibility** and **Make APKs**. The builder starts the temporary access it needs during the build. It does not install the generated APKs on the other device.

## Current status

Version **0.7.17** (`versionCode` 24) can use an already open, verified root ADB session without closing it. It also starts the builder's temporary access when ADB is off. Catalog revision 3 adds Heat Live 6.0.0 and HeatVod 6.9.9. **Check compatibility** downloads reviewed entries and matching neutral templates; existing built-in versions remain available offline. The builder still rejects an unreviewed app update. See [what changed in 0.7.17](docs/RELEASE-0.7.17.md) and [supported versions](docs/SUPPORTED-APPS.md).

On 2026-10-16, the builder matched all six installed originals on a V6 Plus with Heat Live 5.9.5. Selecting Heat Live alone saved one APK to Downloads. That APK passed signature checks, installed, and launched to the standard Android media permission screen on the source Plus. At the owner's request, this update was not tested for playback on a separate device.

On 2026-11-13, version 0.7.17 matched all six installed originals on the V6 Plus and exported all six APKs to Downloads while the separately opened root ADB port stayed connected. Separate-device playback of these newly updated builds was not tested. Earlier, the owner tested version **0.7.12-design-preview** on a factory-reset V6 and a 2017 Shield and reported that all six APKs worked there.

## Use on your own box

1. Use a V6 with the original apps you want to build installed and working. Heat Live must be present to read the box setup. See [supported versions](docs/SUPPORTED-APPS.md).
2. Install the builder APK on that V6 and read the first-launch testing notice. Continue only if you agree.
3. Select **Check compatibility**. If the TV remote merely highlights the button, press OK again.
4. Select the matching app tiles you want to build, then select **Make APKs**. Wait for the completion message.
5. Open the dated `Vsee-Apps-...` folder in Downloads. Copy the generated APKs to your other device and install them with its normal installer.

The generated APKs contain information from the owner's V6. **Do not upload or share them.** A new signing key after a factory reset may require uninstalling older generated apps before installing replacements; that can erase their app data.

This tool is for authorized testing. Using modified apps may affect service or account access. Read the [testing notice](docs/TESTING-NOTICE.md).

## Source and builds

`heat-live-collector/` holds the current builder source and neutral app templates. `compatibility/` holds the public signed update catalog. `pc-only-helper/` holds the relay code that is packaged inside the builder. `tools/build-integrated-builder.py` assembles, merges, aligns, signs, and checks the installer. See [building from source](docs/BUILDING.md). A plain Gradle build omits the relay and is not the complete installer.

This repository starts with a fresh history of the current version. It does not include owner profiles, generated owner APKs, private signing keys, device logs, or older prototypes.

## License and third-party material

Original code and documentation authored by TORR3NT's Tech are under [Apache License 2.0](LICENSE). The included Heat/VSee templates, generated app contents, and any separately owned helper material are **not** granted under that license. See [third-party notices](THIRD_PARTY_NOTICES.md). The project owner reports permission to publish the included material; downstream users must confirm any rights needed to reuse or redistribute it.
