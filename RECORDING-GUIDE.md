# Camera Tester 0.4.2 - six-camera recording test

**Menu → Record** records one to six selected TEYES channels as separate H.264 MP4 files on removable USB storage. No root, Developer Options or audio permission is required. Application controls are in English.

The six-camera preview route was verified on my CC4 PRO on September 11. On September 14, version 0.4.2 completed two automatic transitions and finalized three six-camera segment sets at 720p / 6 Mbps each: two full three-minute segments and a roughly 100-second stopped segment. No automatic error occurred. The previous slow-finalization handling was corrected, while measured transition gaps of about 4.5 seconds and variable surround-camera output FPS remain. See the [latest device results](diagnostics/2026-09-14-rollover-success.md), [rollover analysis](diagnostics/2026-09-14-rollover-failure.md) and [earlier recording results](diagnostics/2026-09-14-six-camera-recording.md). Recovery from a native cleanup exceeding eight seconds is covered by local tests but was not needed during this device run.

## First test

1. Install `Camera-Tester-0.4.2-background-debug.apk` over the existing app, keeping its saved channel choices.
2. Open **Record** from the top-right hamburger menu. Preview stops when entering recording mode.
3. Tap **Choose USB drive**, select the mounted drive by name and UUID, and grant Android access to its root or its `6camdvr` folder. The app creates that folder if needed; an existing empty folder is also accepted.
4. Select camera checkboxes, from one to six. Defaults: all six, **1280 × 720, 25 FPS, 3 Mbps per camera**. Camera selections and profiles are saved.
5. Run **Benchmark selected cameras + USB**. It writes and synchronizes 64 MiB, then records the selected cameras for about 30 seconds after all channels supply frames. Temporary files are removed afterward. Both existing `last` and `current` recordings are preserved.
6. Tap the **small solid white circle** to start the three-minute loop. During preparation, controls are disabled. Once every selected channel supplies encoded frames, the control becomes a **small solid red square**. Tap it to stop; wait for **Ready** before removing the drive.
7. For this rollover regression test, use the same six-camera 720p / 30 FPS / 6 Mbps profile and record for at least seven minutes, then stop. Check both folders and play every selected camera's MP4.
8. Use **View recording / benchmark report** on the head unit or **Menu → Export → Export reports** to save a named copy to a chosen destination. The app also saves `6camdvr/recording-report-latest.txt` when the USB remains writable.

Recording continues when you leave the screen, press Home/Back, switch applications or remove the task from Recents. The foreground service owns capture and keeps a partial wake lock until camera and file cleanup completes. The notification opens the DVR screen and has an explicit Stop action. Returning to Record restores the current state, selected profile and live measurements. No audio is recorded.

Grant Camera permission when starting the camera foreground service. Allow notifications to access its Open/Stop controls; if notifications are denied, return to Record to stop. Live preview and camera tests are blocked while DVR owns the feeds so they cannot replace encoder surfaces. Recording mode displays measurements instead of live pictures.

This is not automatic boot recording. Force-stop, power loss, process termination or a TEYES producer failure can still end capture. Background recording on the target firmware must be verified separately from local lifecycle tests.

## USB selection and formatting

The selector lists mounted removable volumes and UUIDs. A separate inventory shows USB mass-storage devices and bus paths. Public Android APIs do not reliably associate a volume with a physical TEYES USB socket, so the app does not invent USB 1/2/3 assignments. Select the previously reported SanDisk drive when Android exposes it as a mounted volume.

**Format USB…** displays the selected drive's name and UUID and warns that formatting erases all its files. It opens Android storage settings; select the same drive there and use Android's format confirmation. On return, choose the drive and grant folder access again, even if you cancelled formatting.

The app does not format a disk itself or assume formatting succeeded. Android's direct disk-format APIs require privileges. The extracted TEYES format command uses a global DVR destination rather than the selected drive, so it is not used. If this firmware does not expose formatting in storage settings, use its original storage utility or a PC.

## Retention and filenames

```text
USB drive/
  6camdvr/
    last/                     previous finalized segment
      adas_front.mp4
      adas_rear.mp4
      360front.mp4
      360left.mp4
      360right.mp4
      360rear.mp4
    current/                  recording now, or finalized when stopped
      [the same filenames for selected cameras]
    recording-report-latest.txt
```

Only checked cameras get files. Names are fixed in both folders. Hidden ownership and segment metadata allow recovery; do not edit them or place unrelated files in the segment folders. Existing unowned nonempty folders are refused rather than erased.

At an automatic boundary, all selected MP4s are finalized before replacing `last`. The next set starts in `current`, and the older retained set is removed. A transient `previous` directory permits recovery during rotation.

Stop finalizes the shorter segment in `current`, keeping `last`. Starting the next normal recording promotes finalized `current` to `last`, replacing the older `last`, before opening fresh files. An unfinished `current` left by a crash is discarded on the next normal recording; finalized `last` is preserved. Failed camera/encoder segments are not promoted. Benchmarks use separate temporary `benchmark-videos` files and do not rotate retained recordings.

The capture timer starts when all selected encoders have produced a frame. Channels start sequentially, so an early camera can have extra startup footage. At each boundary the app stops producers, drains encoders, synchronizes files, rotates folders and reconnects. **There is a recording gap between segments.** The report measures shutdown-to-all-channels-ready time. This is a conservative transition measurement, not frame-perfect synchronization; seamless capture and exactly 180.000 seconds per MP4 are not promised.

If finalization exceeds eight seconds, the app shows a waiting state and saves pending phase details internally. It keeps files open until all encoder workers finish and uses their final results to decide whether to retain the segment. A slow cleanup alone does not stop the loop. A native call that never returns keeps the app in this waiting state; files cannot be safely reused while their workers still own them. Genuine codec, writer or camera errors still stop the recording.

An active MP4 may not play until Stop/rollover finalizes its metadata. At the default profile, six cameras request about 2.25 MB/s and 405 MB per three-minute set before overhead. Keep at least 1 GB free for two sets and reserve. Free space is checked before each segment when Android can report it.

## Interpreting the benchmark

- **USB MiB/s, write and fsync:** sequential throughput includes explicit synchronization. Less than 1.5 times the configured aggregate video bitrate is flagged. A short test may miss sustained-write slowdown and does not guarantee equal performance with six concurrent files.
- **Output FPS / PTS FPS:** delivered encoded cadence and cadence represented by video timestamps. Live output FPS includes current stalls; finalized capture cadence excludes shutdown time. A 25 FPS source cannot supply 30 distinct frames per second. Low FPS can come from the source, encoder or storage.
- **Camera input / encoder input:** preview frames consumed by the image bridge and frames submitted to the encoder. Zero camera input points to the vendor preview stage; positive camera input with no submissions points to rendering; positive submissions with no encoded output points to the encoder stage. Coalesced callbacks indicate preview notifications combined while processing; they are not an exact count of frames lost by the sensor.
- **Write mean / p95 / maximum:** time inside MP4 sample writes, not individual physical USB write latency. Long delays plus weak USB throughput suggest storage pressure. Final flush time contributes to total segment and rollover time.
- **Codec / hardware / max instances:** selected encoder and advertised concurrency. Supported software encoders can be attempted if hardware initialization fails and are identified in the report. Advertised counts do not guarantee that the firmware has enough resources.
- **App CPU / memory / thermal status:** CPU covers this app only, where one fully busy core is 100%; it excludes the TEYES producer. Android thermal status: 0=none, 1=light, 2=moderate, 3=severe, 4=critical, 5=emergency, 6=shutdown. Firmware may not supply useful thermal data.
- **First-frame delay / frame gaps / timestamp corrections:** indicate slow startup, stalls or unusual source timestamps.
- **Shutdown phase / durations:** identify time spent waiting for encoder EOS, finalizing/releasing the MP4 writer and stopping/releasing the codec. Pending phase durations are live observations, not proof of failure. These measurements are separate from sample-write latency.

Compare one, two, four and six cameras with the same profile. If USB speed has margin but FPS falls as cameras are added, compare encoder choice, CPU, temperature and source load. If writes stall, try another drive or a lower bitrate. Lower resolution reduces encoding demand. The report identifies possible limits rather than claiming a unique diagnosis from one measurement.

The original TEYES DVR may also consume encoders or USB bandwidth; this app does not change its recording settings. Note whether it was running when comparing results. Opening a TEYES channel here may replace another app's preview, as in the previously tested direct-preview mode.

## Validation

JVM/Robolectric tests cover camera selection, UI states, storage ownership and recovery, retention, isolated benchmarks, encoder lifecycle and measurement calculations. Build and Android Lint are run before delivery. These checks cannot validate proprietary TEYES production of frames, encoder concurrency or actual USB performance.

For the background build, start all six cameras, press Home and use another application across at least two recording boundaries, return to Record to confirm continuing counters, stop from the notification, export the report, confirm each selected MP4 plays with the expected view, and check the original DVR/360 after stopping the tester.

Android interfaces: [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec), [MediaMuxer](https://developer.android.com/reference/android/media/MediaMuxer), [StorageVolume](https://developer.android.com/reference/android/os/storage/StorageVolume), [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files).
