# WhatsApp
Automatic WhatsApp sending requires an official provider and server-side credentials. The Android app never stores an access token. It creates a durable notification/report job, then calls the backend when online.

If credentials are absent, the provider endpoint returns a clear not-configured error and the local attendance system continues normally. WhatsApp is never described as working offline.
