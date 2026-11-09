# Attendance two-stage implementation plan

Goal: implement approved teacher -> education -> business admin attendance, stable lesson roster and local todo.
Spec: ../specs/2026-11-06-attendance-two-stage-review-design.md (approved 2026-11-06).
Constraints: local only; no remote deployment; existing credits unchanged; historical confirmations remain valid; no name based student identity.

- [x] Backend review: extend existing review service with explicit stage NEW/PENDING_FIRST/PENDING_FINAL/FINAL/RETURNED, validate roles and images, version guards, preserve legacy confirmations. Update web and miniapp endpoints consistently; only final records count toward formal stats/salary. Test permissions, return/retry, images and legacy behavior.
- [x] Lesson roster: persist stable course occurrences and effective membership; expose /miniapp/staff/attendance courses, lessons, roster and roster changes plus /miniapp/attendance/todos. Use existing ScheduleCalendar expansion; preserve original lesson identity under adjustments, filter teacher roster, require student/enrollment identity and valid quarter/lesson. Tests effective range, removal, same names and calendar change. Coordinate shared service integrations with review worker.
- [x] Frontends: adapt existing miniapp teacher sign, review/list/detail, home role todo counts; education roster membership editor with start/end lesson and linked student selector. Web review and roster must match backend. Tests role transitions, todo navigation and roster edit. Preserve login.
- [x] Integration: run existing and new node/java isolated MySQL suites and production frontend build; apply migrations only local after verifying configuration. Independent review and fix findings. Leave local branch ready for user testing; no PR unless requested.

## Local verification — 2026-11-06

Implemented locally; no push or deployment. Applied both attendance migrations to the configured local database; existing business row counts and credit balances unchanged. Backend and web restarted; HTTP 200 on ports 8080 and 8081.

Verification: 51 Java tests across eight targeted suites passed; miniapp 414 tests passed; production web build passed; targeted web attendance/finance 54 tests passed. Full web suite has one independently reproduced baseline one-to-one test harness failure (JSON undefined), unrelated to this feature. Independent review findings resolved. Login files unchanged. Actual phone testing remains for user acceptance.

## GitHub integration verification — 2026-11-09

User requested a PR, and merge to main if no collaborator PRs are open. Refreshed validation: miniapp 434 tests, web 102 tests, eight targeted isolated-MySQL Java suites (51 tests), and production web build passed. The existing one-to-one web test fixture now supplies the positive unit price already required by main; product behavior was unchanged. Login sources unchanged.

Includes attendance category/date filtering, explicit course selection, native media picker lifecycle fixes, current-workspace review controls and consistent miniapp attendance UI. Example records remain only in the local database; no dumps, credentials or generated deployment artifacts are included. Server and WeChat publication are outside this integration request.
