# MAG Recorder

Native Android magnetometer recorder for testing whether the phone magnetometer can capture respiration-related motion or weak cardiac-related components.

Current stable test build: `0.3.0`.

The app now opens through a safe launcher screen first. The magnetometer is not accessed until the recorder screen is opened and Start is pressed.

The app records the phone's magnetic field sensor and exports samples in CSV format with metadata header lines.

## Features

- Safe launcher screen that does not access sensors.
- Separate magnetometer recorder screen.
- Records calibrated `TYPE_MAGNETIC_FIELD` when available.
- Falls back to `TYPE_MAGNETIC_FIELD_UNCALIBRATED` if a calibrated magnetometer is unavailable.
- Captures all three axes: `Bx_uT`, `By_uT`, `Bz_uT`.
- Computes `B_abs_uT = sqrt(Bx^2 + By^2 + Bz^2)`.
- Saves Android sensor timestamps in nanoseconds plus a relative `time_s` column.
- Saves sample-to-sample interval `dt_ms` so the real sampling rate can be checked offline.
- Uses Android's built-in save-file picker for CSV export.
- Includes subject/session ID and notes fields for posture, placement, task, and reference-device comments.

## Exported CSV columns

```text
time_s,sensor_timestamp_ns,system_start_unix_ms,Bx_uT,By_uT,Bz_uT,B_abs_uT,bias_x_uT,bias_y_uT,bias_z_uT,accuracy,dt_ms
```

For calibrated magnetometer recordings, the bias columns are `NaN`. For uncalibrated magnetometer recordings, Android may provide estimated hard-iron bias values.

## Build locally

Install Android Studio or Android command-line tools with a recent Android SDK, then run:

```bash
gradle :app:assembleDebug
```

The APK will be created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Build from GitHub Actions

Two workflows are included:

- `Android build`: compiles the app and uploads `mag-recorder-debug-apk`.
- `Android emulator smoke test`: installs the app on an emulator, opens the launcher, and verifies that the recorder screen opens.

Only install an APK after both workflows are green.

## Experimental protocol notes

For respiration experiments, keep the phone and body placement reproducible:

1. Record a reference respiratory belt / spirometer / capnography signal at the same time.
2. Log posture and phone position in the notes field.
3. Avoid magnetic cases, speakers, metal tables, laptops, charging cables and moving ferromagnetic objects.
4. Test multiple breathing tasks: normal, slow, fast, deep, breath-hold and recovery.
5. Use the exported `dt_ms` column to verify that the actual sampling is stable enough before signal processing.

Important: phone magnetometers usually measure ambient field changes in microtesla. If a permanent magnet is attached to the chest/abdomen, the method becomes magnetic displacement tracking rather than intrinsic biomagnetic sensing. Keep this distinction clear in the paper.
