# Mobily SIM auto-connect verification — 2026-11-06

## Intended behavior

- The rule is opt-in and identifies the active data SIM, not the currently registered roaming network.
- On Android 11/API 30+, use the active-data subscription; on older supported releases, fall back to the default data subscription.
- A recognizable Mobily / Etihad Etisalat / موبايلي SIM triggers the saved native WireGuard profile whether the account has balance or not.
- Balance, phone number, SMS, APN, carrier billing and portal state are never queried.
- A different active data SIM, no active SIM, or a Wi-Fi/Ethernet route does not trigger the Mobily rule.
- Only a tunnel started by the automatic rule may be stopped by a SIM/network change. A manually started tunnel is not silently stopped.
- VPN consent and Phone permission are separate Android permissions. Background boot handling never opens a consent screen; the user must open the app if consent is missing.
- A no-balance SIM can follow the same start decision, but WireGuard still needs an actual mobile-data path to complete its handshake and carry traffic. This feature does not bypass carrier billing, access controls, or network restrictions.

## Required physical-device checks

Device target: Motorola moto g35 5G, Android 15.

| Test | Expected result | Evidence |
| --- | --- | --- |
| Enable rule with active Mobily SIM and Phone permission | Mobily SIM identified; VPN consent requested once | Screenshot / non-secret status |
| Grant VPN consent | Saved native WireGuard profile starts | Handshake and non-secret status |
| Mobily SIM without usable data service | Same start decision; no successful handshake is claimed | Status and handshake timestamp |
| Change active data SIM to non-Mobily | Auto-start rule stops only its own tunnel | Status and peer counters |
| Remove Mobily SIM | Auto-managed tunnel stops after SIM-state event | Status |
| Reinsert Mobily SIM / set as active data SIM | Auto-start is retried | Handshake |
| Wi-Fi becomes active | Mobily auto-rule pauses; manual tunnel is not altered | Status |
| Disable the auto rule | No further automatic start | Status |
| Reboot after prior VPN consent | Rule is reevaluated after boot | Status |
| Deny Phone permission or revoke VPN consent | Fail closed; no automatic start | Status |

## Current evidence

- Source-level carrier policy tests and Android build/lint are tracked by the Android native workflow.
- Physical SIM, dual-SIM, Wi-Fi/mobile handoff, background/boot and live server-handshake checks are NOT RUN until executed on the named handset.
- Do not mark Mobily device compatibility as verified based on compilation or emulator tests alone.
