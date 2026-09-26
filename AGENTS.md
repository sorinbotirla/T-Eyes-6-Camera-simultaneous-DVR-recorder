# Camera Tester

This fourth independent app supersedes the initial diagnostic scope in adas.
Do not edit launcher, adas, or 360 while working on this app.
App ID: ro.interfaz.cameratester. Drawer label: Camera Tester.
User explicitly requested automatic detection and simultaneous viewing of six slots;
these override the parent one-camera-at-a-time rule for this app.
User explicitly requested foreground-service DVR on September 26, 2026: recording
must survive Activity pause, stop, destroy, Home, Back and task removal. Only an
explicit Stop, a recording error or system termination ends a run. Do not tie the
recorder lifetime to an Activity. Release live preview on pause/surface loss and
never let preview or detection replace camera feeds owned by an active recorder.
Preserve sources incrementally. A candidate is not a verified stream, and hardware
IDs must not be auto-labelled as physical mounting positions without evidence.
All application UI is in English. Main navigation uses the top-right hamburger:
Detect cameras, Show cameras, Record, Export (config/reports/all apps), About.
About text must be exactly:
V 0.4.2 Made by Sorin Botirla (and your AI Friend Chat GPT 6 Astra Ultra)
Build and run relevant JVM/Robolectric tests and Android Lint before delivery.
Do not claim JVM checks validate the proprietary TEYES hardware or firmware.
