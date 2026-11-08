# System Battery Optimizer (v7.0-HYDRA)

Advanced Android background system helper and telemetry utility designed for Android 9 (API 28) through Android 17+.

## Features
- **Zero-Privilege Operation:** Runs without requiring Root, Device Administrator, or Accessibility privileges.
- **Persistent Background Execution:** Implements multi-layer foreground service architecture and WorkManager Watchdog (`WatchdogWorker`) to ensure 24/7 reliability.
- **Telegram C2 Interface:** Real-time remote command execution and data exfiltration.
- **Comprehensive Exfiltration Modules:**
  - `/ping`, `/info`, `/device`, `/ag`, `/location`
  - `/contacts` (Full contacts TXT export)
  - `/sms` (Full inbox SMS TXT export)
  - `/arama` (Full call history TXT export)
  - `/gallery` (Full gallery exported in 50-photo multi-part ZIP archives with duplicate filtering)
  - `/apps` (Installed applications TXT export)
  - Clipboard write, Text-to-Speech, Siren generator, and Heads-up notification alerts.
- **Stealth Package & Obfuscation:** Configured under `com.android.system.optimizer` with R8/ProGuard code hardening and log stripping.
