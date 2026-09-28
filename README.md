# T'Eyes CC4 PRO Camera Tester

Android camera discovery, six-camera TEYES preview and a looping USB DVR. <br />
Root and Developer Options are not required. <br />
It's strongly recommended to use a USB 3.2 Stick plugged in the port labelled "USB1".<br />
Stop the T'Eyes DVR recording when using this app to free up some bandwidth.

## Current build

This app allows simultaneous record from all the 6 cameras, <br />
the ADAS Front, <br /> 
ADAS Rear,  <br /> 
360 Front,  <br /> 
360 Left,  <br /> 
360 Right  <br /> 
and 360 Rear.  <br /> 
See [recording details](RECORDING-GUIDE.md).

## Navigation

Open the menu icon at the top right:

- **Detect cameras** inventories sources, saves discoveries incrementally, and
  provides manual testing and assignment. A discovered source is not marked as a
  working stream until frames arrive.
- **Show cameras** opens the six TEYES positions. Tap a tile for an individual
  camera; use Back or **Show cameras** to return to all six. **Camera options** in
  the same menu contains channel selection, preview stop and status refresh.
- **Record** selects one to six cameras, resolution, FPS, bitrate and removable
  storage. It also contains USB formatting through Android settings and the
  camera/USB benchmark.
- **Export → Export config** saves camera discoveries, mappings and recording
  settings as JSON. **Export reports** saves the selected report as text. Android
  lets the user choose the filename and destination.
- **Export → Export all TEYES apps and services** exports all visible installed
  packages and accessible service/framework code to one ZIP, without a package
  whitelist or application-imposed file/total byte limit. Denied reads are logged.
- **About** shows the requested version and author credit.

## Background recording

Start recording with the white circle. Once capturing, the control is a red square.
Home, Back and switching applications leave recording running in a foreground
service. Reopen **Record**, or use the notification's **Stop** action, to finalize
files. A partial wake lock is held through capture and file cleanup.

Android camera permission is required for the camera foreground service. On
Android 13, notification permission is requested so the Open/Stop notification
can be shown. Denying notifications does not itself stop recording.

Preview/testing is paused while recording owns the feeds: a second TEYES preview
surface can replace the recorder's surface. No recording is started automatically
at boot or after process termination. Power loss, force-stop, vendor-service
failure or the firmware killing the app can still interrupt capture.

## Files

Clips are H.264 MP4 without audio, in `6camdvr/current` and `6camdvr/last` on the
selected USB volume. The fixed filenames are:

`adas_front.mp4`, `adas_rear.mp4`, `360front.mp4`, `360left.mp4`,
`360right.mp4`, `360rear.mp4`.

The latest completed set and current set are retained. Benchmarks use separate
temporary clips. Existing storage ownership safeguards are retained.
See [USB recording and benchmark](RECORDING-GUIDE.md),
[TEYES preview](TEYES-PREVIEW-GUIDE.md), and [exports](EXPORT-GUIDE.md).

## Discovery and platform limits

The generic detector covers public Camera2/physical IDs, Legacy Camera, USB UVC,
readable V4L2 nodes, local network stream clues, installed applications, Binder/HAL
inventory and readable system information. Local probes are bounded and do not
scan the LAN. Known URLs can be entered manually. Discovery's bounded APK clue
scan is separate from the full, uncapped export workflow.

The TEYES adapter uses the analyzed exported DVR service and a verified channel
profile. Different firmware may require a new adapter. Private application data,
protected system files and live process memory are not accessible without their
required privileges; exported services consist of their available code and
metadata. Fisheye correction and 360 stitching are not implemented.

## Build

Open this folder in Android Studio. Use its bundled JDK and install SDK platform
35 plus build-tools 36.0.0. The checked-in wrapper uses Gradle 9.3.0 / AGP 9.0.0;
minSdk 26, targetSdk 33, compileSdk 35, Java source level 17.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.
The full project root is `D:\PWR\proj\Codex\teyes\camera-tester`.

Commit the project root including `gradlew`, `gradlew.bat`, `gradle/wrapper`,
Gradle configuration and `app/src`. `.gitignore` excludes local SDK paths, build
outputs, signing keys and private device diagnostics. Keep the current debug
signing key outside Git if future builds must install over existing devices;
a different signing key requires a different installation strategy.

Dependencies and licenses: [third-party notices](THIRD-PARTY-NOTICES.md).
