# Attendance Engine
`AttendanceEngine` computes verified seconds from merged presence intervals, attendance percentage, late minutes, early-leave minutes and a final status from a policy snapshot.

`PresenceStateMachine` implements NOT_SEEN / VERIFYING / PRESENT / TEMPORARILY_MISSING / LEFT / RETURNED / COMPLETED / MANUAL_REVIEW semantics. A loss event first enters a grace window; it is not immediately treated as leaving.

Low confidence is routed to MANUAL_REVIEW. Manual overrides are expected to be accompanied by an audit log. A lecture policy snapshot prevents later configuration edits from rewriting history.
