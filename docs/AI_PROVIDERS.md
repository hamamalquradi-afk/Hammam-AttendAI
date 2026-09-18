# AI Providers

Hammam AttendAI supports OpenAI, Google Gemini, and Anthropic Claude through the existing `AiProvider` abstraction. Cloud AI is optional; the local query assistant remains available without Internet or provider credentials.

## Credential modes

### Local BYOK

The System Owner or a user with `MANAGE_AI_PROVIDER` can configure a provider key. Raw keys are stored only in `SecureSecretStore` under Android no-backup storage and encrypted with Android Keystore. Room, DataStore, backups, diagnostics, audit exports, and source code do not contain the raw key. UI displays only a masked suffix and provides replace/delete actions.

### Backend Managed

Android sends provider/model/task data to the optional backend. Provider API keys remain backend environment variables. Android can optionally authenticate to that backend using a separate access token stored by the same secure secret store; the backend stores only the configured hash in deployment environment variables.

## Dynamic model discovery

Provider model lists are refreshed from official provider APIs or the backend-managed model endpoint and cached locally. The application does not rely on a permanent hard-coded model list. If offline, the cached list is shown; if none exists, the provider remains configured without inventing model identifiers. Manual Model ID remains an advanced fallback.

Assignments support a default assistant model plus report-summary and complex-analysis overrides. The latter may remain `USE_DEFAULT`.

## Grounding and authorization

Cloud models do not receive unrestricted SQL access. Attendance/statistics tools query Room through authorized functions and apply the current role/scope before returning data. Student tools are self-only; teachers and representatives are scoped; System Owner/Administrator can use global scope when authorized.

Write requests remain draft/confirmation flows rather than direct model mutations.
