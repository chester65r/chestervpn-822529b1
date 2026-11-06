# Tunnel Console (Android)

**Tunnel Console** is a modern, mobile-first network optimization and VPN dashboard built with **Kotlin** and **Jetpack Compose**, integrated with **Firebase** and **Google Gemini API** (`gemini-3.8-flash` via Firebase AI Logic).

## Features

- **Real-Time Tunnel Dashboard**:
  - Live animated connection status indicator with pulsing ring feedback
  - Connection duration timer (HH:MM:SS)
  - Real-time download & upload telemetry with total throughput tracking
  - Custom Jetpack Compose Canvas dual-channel speed graph
  - One-tap Connect / Disconnect controls
- **Gemini AI Connection Advisor**:
  - Powered by Google Gemini (`gemini-3.8-flash`) via Firebase AI Logic
  - Analyzes endpoint configurations, SNI virtualization certificates, TLS handshake headers, and real-time bandwidth metrics to deliver actionable diagnostics
- **Conduit Configuration**:
  - Route presets: TLS Virtualization (Alpha), Edge CDN Routing (Beta), Cloud Delivery (Gamma), and Custom Infrastructure Gateway
  - Server IP/Domain, Port (1–65535), and SNI Host input with real-time input validation
  - Handshake payload editor with macro insertion (`[host]`, `[crlf]`)
  - Live parsed payload preview with explicit line breaks
- **Cloud & Relay Server Management**:
  - Synchronized with **Firebase Firestore**
  - **Google Sign-In** authentication via Android Credential Manager
  - Quick-start VPS presets (US East, Europe, Asia)
  - Custom relay server registry with instant 1-tap "Use in Configure"
- **Profile Sharing & Import/Export**:
  - Full-profile `TUNNEL://` Base64 conduit code generation and Android clipboard copying
  - Safe import parser with syntax verification
  - Persistent saved profiles library
- **WireGuard Blueprint Hub**:
  - Complete code browser for audited WireGuard Android tunnel architecture (`TunnelController.kt`, `SecureConfigStore.kt`, `server-setup.sh`, `wg0.conf`)
  - Android native verification checklist

## Tech Stack

- **UI**: Jetpack Compose, Material 3 (M3)
- **Language**: Kotlin 2.0.21
- **Platform**: Android SDK 35 (Min SDK 26)
- **Backend & Cloud**:
  - Firebase AI Logic (`gemini-3.8-flash`)
  - Firebase App Check (Debug & ReCAPTCHA Enterprise attestation)
  - Firebase Authentication (Google Sign-In via Jetpack Credential Manager)
  - Firebase Firestore (Cloud Relay Server persistence)
- **Networking & VPN**: Android `VpnService` with TLS / WebSocket socket conduits
