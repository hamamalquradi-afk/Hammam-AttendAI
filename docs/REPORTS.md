# Reports
Report settings are per teacher and optionally per subject. Daily, weekly, monthly, semester and custom jobs are represented in Room. Multiple frequencies can coexist through multiple settings/jobs.

Jobs use a deterministic SHA-256 deduplication key over teacher, subject, report type and period. Offline jobs can generate locally and wait in PENDING_SEND. Approval-required jobs remain PENDING_APPROVAL until a user explicitly approves them.

Android PDF generation uses platform text layout with RTL direction for Arabic shaping. CSV output is suitable for spreadsheet import.
