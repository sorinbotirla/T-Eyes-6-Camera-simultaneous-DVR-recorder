# Camera Tester 0.4.2 — exports

All export actions are under **Menu → Export**. The Android document picker lets
you choose the filename and destination, including USB storage exposed by the
firmware. Files are written directly to that destination.

## Export config

Choose **Export config**, then **Choose destination and export config**. The JSON
contains the saved source registry, generic camera mappings, TEYES channel
choices, selected cameras, video profile and USB volume reference. Auto channel
choices remain automatic; observed routing is included in saved evidence.
Exporting does not scan or open cameras. It does not change saved scan state.

The USB URI is a reference only. A JSON file cannot transfer Android storage
permission to another installation; that installation must choose its USB folder.
This build exports configurations; it does not add a configuration import command.

## Export reports

Choose **Export reports**, select the report and choose a filename/destination:

- All available reports
- Camera detection report
- TEYES preview report
- Recording / benchmark report

Reports include the available saved results. During an active recording, this is
the last saved snapshot, not a continuous log subscription. Missing saved data is
reported explicitly. The main Record screen can also display its last saved report.

## Export all TEYES apps and services

Choose **Export all TEYES apps and services**, then **Choose destination and export
ZIP**. The export inventories every package visible in the current Android user
profile, including non-TEYES packages and system apps. It attempts all reported
base/split APKs, native/shared libraries and readable framework, service binary,
init and APEX files. There are no application-imposed keyword, file-count,
individual-file-size or aggregate-size limits.

The ZIP is streamed directly to the selected destination; no complete archive is
staged in internal cache. Diagnostic command output uses temporary cache files,
with an explicit ten-second execution timeout per command. A command timeout and
its captured output are included in the archive.

The archive includes:

- `packages/`: installed APKs, split APKs and native libraries.
- `system/`: readable framework/vendor/service code and dependencies.
- `diagnostics/`: package components/permissions, Binder/HAL/process inventory,
  camera configuration and saved reports.
- `manifest.tsv`: original source, ZIP entry, bytes, SHA-256 and complete/partial status.
- `collection-log.txt`: unavailable paths, read failures, directory aliases and
  timed-out diagnostics.
- `export-summary.txt` and `README.txt`: completion counts and scope.

Services are represented by their code and metadata. Android-protected files,
private app data and running process memory are not made readable by this export.
An unreadable directory is counted as unavailable; the app cannot count the
unknown files hidden inside it. Partial source reads are marked PARTIAL. A
failure writing the destination aborts the export instead of reporting success.

Keep the app/device running during a large export. UI recreation and navigation
reattach to the same process-owned worker; this export worker is not itself a
foreground service. Process termination interrupts it and the next opening reports
that the destination may be incomplete. Cancel requests a cooperative stop and
may need to wait for a blocked storage call to return. Incomplete destination
files are retained with an explicit failure/cancel message; choose a new filename
when retrying. No source application files are changed or deleted.
