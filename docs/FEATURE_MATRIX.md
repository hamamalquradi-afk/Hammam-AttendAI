# Feature matrix
| Area | Implementation |
|---|---|
| Offline academic data | Room entities and repository foundation |
| Attendance state machine | Implemented and pure-Kotlin tested |
| Confidence / anti-fraud | Implemented as review signals, not accusations |
| BLE | Scanner, advertiser, rotating token abstraction, foreground service |
| QR/NFC | Time-bound QR verifier + NFC interface |
| Reports | Jobs, settings, dedup keys, RTL PDF generator |
| Notifications | Durable queue + online processor |
| Sync | Durable queue + conflict-ready version model |
| AI | Offline intent parser + read-only provider/tool abstractions |
| Backup | Encrypted local backup/restore primitive |
| Web | React/Vite admin shell |
| Backend | `/api/v1` provider relay, idempotency, auth, rate limiting |
| CI | Android debug APK artifact + service build workflow |
