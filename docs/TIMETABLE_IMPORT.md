# Weekly Timetable Import

Timetables are versioned by academic scope and week. Historical approved weeks are not rewritten when a future schedule changes.

## Input sources

The Android flow supports:

- camera capture;
- gallery/image selection;
- PDF/document selection through modern Android document APIs;
- manual entry/edit.

Broad storage permission is not required.

## Pipeline

`SOURCE → EXTRACT → DRAFT → REVIEW → VALIDATE → APPROVE → MATERIALIZE FUTURE LECTURES`

A selected source can always be saved as a local draft. Text/vision extraction is optional. When cloud vision is unavailable, the user can continue with manual review/editing rather than losing the import.

Validation covers group/teacher overlaps, duplicate lectures, start/end errors, unknown or archived subjects, teacher-subject relationship issues, and week-range mismatches. Conflicts keep the version in a review state.

Future lecture instances are materialized only after approval. Completed lectures and historical attendance are never rewritten. Subsequent approved changes affect scheduled/future lectures only and are audited.

## Authorization

`MANAGE_TIMETABLE` is scoped. A representative may receive it for an assigned group; assistants and teachers do not receive it automatically. Temporary grants use the same scoped-permission infrastructure.

A local WorkManager reminder surfaces missing next-week approval without requiring Internet.
