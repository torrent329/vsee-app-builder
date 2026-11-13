# Supported original apps

The builder includes these versions on the V6 and can fetch signed compatibility updates for later reviewed versions:

| Original app | Version | Generated file |
| --- | --- | --- |
| Heat Live | 5.9.4 | `Heat-Live-V6-Owner-5.9.4.apk` |
| Heat Live | 5.9.5 | `Heat-Live-V6-Owner-5.9.5.apk` |
| HeatVod | 6.9.6 | `HeatVod-V6-Owner-6.9.6.apk` |
| Vod Ultra | 3.8.2 | `Vod-Ultra-V6-Owner-3.8.2.apk` |
| HeatLive Backup | 4.7.8 | `HeatLive-Backup-V6-Owner-4.7.8.apk` |
| Live Ultra | 5.8.0 | `Live-Ultra-V6-Owner-5.8.0.apk` |
| Live Ultra+ | 5.8.0 | `Live-Ultra-Plus-V6-Owner-5.8.0.apk` |

The signed catalog adds these reviewed versions when **Check compatibility** has an Internet connection:

| Original app | Version | Generated file |
| --- | --- | --- |
| Heat Live | 5.9.6 | `Heat-Live-V6-Owner-5.9.6.apk` |
| Heat Live | 6.0.0 | `Heat-Live-V6-Owner-6.0.0.apk` |
| HeatVod | 6.9.9 | `HeatVod-V6-Owner-6.9.9.apk` |
| Live Ultra | 5.8.1 | `Live-Ultra-V6-Owner-5.8.1.apk` |
| Live Ultra+ | 5.8.1 | `Live-Ultra-Plus-V6-Owner-5.8.1.apk` |

Version numbers alone are insufficient: the builder also checks the original app package, signature, and content fingerprint. When Check compatibility runs, it can fetch a signed catalog and matching neutral templates for reviewed newer builds. An app update with no matching reviewed entry is rejected. A vendor or service change can also affect whether a generated app works after installation.
