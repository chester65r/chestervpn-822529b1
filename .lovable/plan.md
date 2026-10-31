# Tunnel Dashboard (web app)

I can't build the carrier-bypass parts of your brief, even for teaching. These are the Mobily and STC presets, the spoofed-host payload and the "free capture portal" option. They would work as a ready-made tool for getting free data from a carrier, whatever the reason for building them. Everything else in your brief is fine, so I'll build that as a clean, realistic tunnel app.

## What gets built (at `/`)
A dark, mobile-first dashboard, laid out like a 9:16 phone app and responsive on desktop:

1. **Home**
   - Status card with a smooth teal "CONNECTED" or muted red/grey "DISCONNECTED" state
   - Simulated download and upload speeds that move while connected
   - A live graph and a large Connect/Disconnect button
   - A short "Connecting..." phase between the two states
2. **Configure**
   - Route preset dropdown with your names: "Route Preset Alpha (TLS Virtualization)", "Route Preset Beta (Edge CDN Routing)", "Route Preset Gamma (Cloud Delivery)" and "Custom Infrastructure Gateway" (blank fields)
   - Alpha, Beta and Gamma autofill neutral example hosts (`alpha.example.com`, `beta.example.com`, `gamma.example.com`), not carrier, WhatsApp or YouTube hosts. Those hosts are what make it a billing bypass, so they stay out under any name.
   - Fields for server IP or domain, port, and server name (SNI)
   - Handshake payload textarea with `[host]` and `[crlf]` macros and a live parsed preview. The default placeholder is `GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf]Upgrade: websocket[crlf]`
   - Validation with clear warnings: required fields, port 1-65535, a valid IP or domain
3. **Share**
   - Export Profile turns the settings into a `TUNNEL://` Base64 code and copies it to the clipboard
   - Import Profile takes a pasted code and fills the form, with an error message if the code is invalid
   - A note that Base64 is encoding, not encryption
4. **Native Android Blueprint** (separate tab)
   - Copyable blocks for `AndroidManifest.xml`, `AdvancedVpnService.kt` and the storage class
   - The blocks use your corrected code (balanced braces, `@Synchronized`, foreground service, own app excluded from the VPN)
   - It has no spoofed-host or carrier presets

The simulated speeds are only visual. A website cannot create a real VPN.

## Not included
- Carrier presets (Mobily, STC) and default spoofed-host handshakes
- Instructions for getting free data
- An APK. This environment has no Android build tools

## Technical details
- TanStack Start routes: `/` (the dashboard, with tabs inside the page), with its own `head()` title and description
- Settings are kept in `localStorage`, read after the page loads
- Colours go in `src/styles.css` as design tokens (dark theme, teal accent, red/grey for disconnected)
- The speed graph is drawn with plain SVG, no new packages
- A small test checks the export/import round trip, port validation and `[host]`/`[crlf]` replacement
