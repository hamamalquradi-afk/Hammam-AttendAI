# Offline Sync
Every important local mutation is committed to Room first. Remote operations are queued with payload, retry count, status and idempotency key. WorkManager with a network constraint retries when connectivity returns.

Conflict resolution uses version + updated time + device context. A conflict that cannot be resolved deterministically is marked NEEDS_MANUAL_REVIEW rather than overwriting silently.
