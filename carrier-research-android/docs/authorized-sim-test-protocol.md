# Authorized SIM Test Protocol

## Scope

Use only SIM accounts and endpoints for which the tester is authorized. This protocol records ordinary connectivity and health-endpoint reachability. It does not test or attempt to defeat a carrier's package or billing enforcement.

## Record for each carrier run

- Tester and UTC timestamp.
- Device model and Android API level.
- Manually selected carrier label (STC, Mobily, or Other).
- Manually declared subscription category (not verified by the app).
- Android-reported active transport, Internet capability, validation, and metered state.
- Authorized HTTPS health endpoint result, HTTP status, DNS record count, and elapsed time.
- Whether the test used cellular data or Wi-Fi.

## Repeatability

Keep the endpoint and device constant when comparing SIMs. Turn Wi-Fi off manually for cellular measurements. Run a single health check per measurement and avoid concurrent downloads or background tests. Repeat a run only when recording a distinct timestamped sample.

## Evidence interpretation

- Cellular means Android reports that the active network uses cellular transport.
- Validated by Android is an operating-system connectivity result, not a carrier billing confirmation.
- Metered is Android's network-cost classification and is not the subscriber's exact package state.
- A successful HTTPS request shows that the specified server responded over the current network path.
- Local policy tests prove only that the policy-engine code matched its expected laboratory decisions.

Carrier-side conclusions require authorized access to the operator's policy decision, charging, and usage-record evidence. Do not infer a billing bypass from an app-side connection test.
