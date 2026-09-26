# Third-party components

- UVCAndroid 1.0.13, shiyinghan / herohan, Apache-2.0. https://github.com/shiyinghan/UVCAndroid
  Includes libuvc (BSD-style), libusb (LGPL-2.1), libjpeg-turbo (IJG/BSD/zlib), libyuv (BSD-style), RapidJSON (MIT and third-party notices).
  Corresponding native source and build instructions: https://github.com/shiyinghan/UVCAndroid/tree/master/libuvccamera/src/main/jni
  The shipped native libraries are unmodified and dynamically linked. Source and license notices are provided for modification/relinking.
- JNA 5.17.0, Apache-2.0 or LGPL-2.1-or-later; this project uses the Apache-2.0 option. https://github.com/java-native-access/jna/tree/5.17.0
- AndroidX Media3 1.5.1, AndroidX dependencies: Apache-2.0. https://github.com/androidx/media/tree/1.5.1
- Test-only: JUnit 4.13.2 (EPL-1.0) and Robolectric 4.14.1 (MIT); not included in the APK.

License texts distributed with this application are in `app/src/main/assets/licenses`.
