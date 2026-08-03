# Pressure + Magnetometer Health Recorder

Native Android recorder for live phone pressure/barometer and magnetometer recordings, with optional linear acceleration and gyroscope logging.

Version 7.1 adds post-recording derived outputs and a guarded startup screen:

- pressure-derived heart-rate windows from the phone barometer, with SQI and above-85 bpm unreliability flags
- magnetometer-derived respiration from the By axis
- on-device plots for pressure HR, respiration rate, and the filtered respiration waveform
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

## Recording Notes

For demonstration recordings, keep phone placement and posture reproducible. Pressure-derived HR is computed after the recording stops in 10-second windows. The Derived tab shows pressure HR before SQI and after SQI; any pressure-HR window above 85 bpm is marked `unreliable_above_85_bpm`. Magnetometer respiration uses the By-axis fixed respiration pathway and is also computed after Stop. The Derived tab changes color when the computation has completed.