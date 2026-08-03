# Pressure + Magnetometer Health Recorder

Native Android recorder for live phone pressure/barometer and magnetometer recordings, with optional linear acceleration and gyroscope logging.

Version 7.3 adds visible derived-signal decisions while preserving the raw phyphox-style export:

- pressure-derived heart-rate windows from the phone barometer, with SQI and above-85 bpm unreliability flags
- magnetometer-derived respiration from the By axis
- on-device plots with rejected SQI windows, pressure peak events, respiration rate, and counted breaths on the filtered waveform
- a phyphox-style ZIP export that keeps raw files separate from derived files

The ZIP contains:

- `Pressure/Pressure.csv` plus `Pressure/meta/time.csv`
- `Magnetometer 1/Magnetometer.csv` plus `Magnetometer 1/meta/time.csv`
- `raw/EventTable.csv`
- `derived/pressure_hr.csv` with before-SQI HR, pressure SQI, after-SQI HR, and reliability status
- `derived/magnetometer_respiration_track.csv`
- `derived/magnetometer_respiration_per_minute.csv`
- `derived/magnetometer_respiration_peaks.csv`
- `derived/summary.csv`

The raw files intentionally keep the phyphox-style two-column sensor tables so existing analysis scripts can still read column 1 as time and column 2 as sensor value. The derived files add HR and respiration without changing the raw export surface.

## Build locally

Install Android Studio or Android command-line tools with a recent Android SDK, then run:

```bash
gradle :app:assembleDebug
```

The APK will be created at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Download from GitHub Actions

Open the Android build run triggered by a branch push. Download the artifact named PRESSURE-MAG-RECORDER-v73-run-<run number>-debug-apk, extract it, and install Pressure-MAG-Recorder-v73-debug.apk. Pull-request runs validate the merged code but deliberately do not publish APK artifacts.

## Recording Notes

For demonstration recordings, keep phone placement and posture reproducible. Pressure-derived HR is computed after the recording stops in 10-second windows. The Derived tab shows pressure HR before and after SQI. Red bands/X markers are windows rejected above 85 bpm; amber bands/X markers are windows rejected for SQI below 0.35. The mobile SQI score combines 60% fused detector confidence with 40% agreement among peak, autocorrelation, and spectral pressure-HR families. The pressure cardiac plot marks peak-detector events. Magnetometer respiration uses the finalized fixed By-axis respiration pathway and marks every counted breath after Stop. The Derived tab changes color when the computation has completed.
