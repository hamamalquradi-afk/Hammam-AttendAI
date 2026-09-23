# Bluetooth LE
BLE is a presence signal, not a distance meter. RSSI is stored only as evidence and is never converted to precise distance.

Representative mode scans only during a lecture window. Student mode can advertise a rotating, non-personal token. Tokens are based on a time slot and per-device secret. No name, university number, phone number, IMEI or MAC-derived identity is broadcast.

Android can throttle or stop background scanning because of Doze, vendor battery policy, Bluetooth state, permissions and process death. The app therefore persists the active attendance session, uses a foreground service when active detection needs it, and offers dynamic QR/manual fallback. Continuous BLE scanning outside attendance windows is intentionally not supported.
