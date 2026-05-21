package com.ajdar.magnetometerrecorder;

import android.app.Activity;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class RecorderActivity extends Activity implements SensorEventListener {
    private static final int CREATE_CSV_REQUEST = 4001;
    private static final int SAMPLE_PERIOD_US = 20_000;
    private static final String VERSION = "0.3.0";

    private SensorManager sensorManager;
    private Sensor magSensor;
    private TextView statusText;
    private TextView valuesText;
    private TextView sensorText;
    private EditText sessionEdit;
    private EditText notesEdit;
    private Button startButton;
    private Button stopButton;
    private Button saveButton;
    private final ArrayList<Sample> samples = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean recording = false;
    private long firstSensorNs = -1L;
    private long lastSensorNs = -1L;
    private long startWallMs = -1L;
    private int accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
    private float bx = 0f, by = 0f, bz = 0f, babs = 0f;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        if (!recording && sensorManager != null) sensorManager.unregisterListener(this);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Magnetometer Recorder");
        title.setTextSize(24);
        root.addView(title);

        sessionEdit = new EditText(this);
        sessionEdit.setHint("Session ID, e.g. S01_rest_supine");
        sessionEdit.setSingleLine(true);
        sessionEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(sessionEdit);

        notesEdit = new EditText(this);
        notesEdit.setHint("Notes: phone position, posture, reference device");
        notesEdit.setMinLines(2);
        notesEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        root.addView(notesEdit);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        startButton = new Button(this);
        startButton.setText("Start");
        stopButton = new Button(this);
        stopButton.setText("Stop");
        saveButton = new Button(this);
        saveButton.setText("Save CSV");
        stopButton.setEnabled(false);
        saveButton.setEnabled(false);
        buttons.addView(startButton, weight());
        buttons.addView(stopButton, weight());
        buttons.addView(saveButton, weight());
        root.addView(buttons);

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setPadding(0, dp(10), 0, dp(8));
        statusText.setText("Press Start to initialise the magnetometer.");
        root.addView(statusText);

        valuesText = new TextView(this);
        valuesText.setTextSize(18);
        valuesText.setPadding(0, dp(8), 0, dp(8));
        root.addView(valuesText);

        sensorText = new TextView(this);
        sensorText.setTextSize(13);
        sensorText.setPadding(0, dp(12), 0, 0);
        sensorText.setText("App version: " + VERSION + "\nSensor is not initialised until Start is pressed.");
        root.addView(sensorText);

        setContentView(scroll);

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { startRecording(); }
        });
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { stopRecording(); }
        });
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { requestSaveCsv(); }
        });
    }

    private boolean setupSensorIfNeeded() {
        if (magSensor != null && sensorManager != null) return true;
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager == null) {
            statusText.setText("Sensor service not available.");
            return false;
        }
        magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (magSensor == null) magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED);
        if (magSensor == null) {
            statusText.setText("No magnetometer found on this phone.");
            sensorText.setText("This phone may not have a compass/magnetometer.");
            return false;
        }
        sensorText.setText(sensorDescription());
        return true;
    }

    private String sensorDescription() {
        String type = magSensor.getType() == Sensor.TYPE_MAGNETIC_FIELD ? "TYPE_MAGNETIC_FIELD" : "TYPE_MAGNETIC_FIELD_UNCALIBRATED";
        return "App version: " + VERSION + "\nPhone: " + Build.MANUFACTURER + " " + Build.MODEL
                + " | Android " + Build.VERSION.RELEASE + "\nSensor: " + magSensor.getName()
                + "\nVendor: " + magSensor.getVendor() + "\nType: " + type
                + "\nResolution: " + magSensor.getResolution() + " µT | Max range: " + magSensor.getMaximumRange() + " µT"
                + "\nRequested sample period: " + SAMPLE_PERIOD_US + " µs. Actual timing is saved in dt_ms.";
    }

    private void startRecording() {
        try {
            if (!setupSensorIfNeeded()) return;
            samples.clear();
            firstSensorNs = -1L;
            lastSensorNs = -1L;
            startWallMs = System.currentTimeMillis();
            recording = true;
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
            saveButton.setEnabled(false);
            sessionEdit.setEnabled(false);
            notesEdit.setEnabled(false);
            sensorManager.unregisterListener(this);
            boolean ok = sensorManager.registerListener(this, magSensor, SAMPLE_PERIOD_US);
            if (!ok) {
                recording = false;
                resetButtonsAfterStop();
                statusText.setText("Could not register magnetometer listener.");
                return;
            }
            statusText.setText("Recording...");
        } catch (Throwable t) {
            recording = false;
            resetButtonsAfterStop();
            statusText.setText("Start failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void stopRecording() {
        recording = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        resetButtonsAfterStop();
        saveButton.setEnabled(samples.size() > 0);
        statusText.setText("Stopped. Samples: " + samples.size() + ". Press Save CSV.");
    }

    private void resetButtonsAfterStop() {
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        sessionEdit.setEnabled(true);
        notesEdit.setEnabled(true);
    }

    private void requestSaveCsv() {
        if (samples.isEmpty()) {
            Toast.makeText(this, "No samples to save", Toast.LENGTH_SHORT).show();
            return;
        }
        String session = clean(sessionEdit.getText().toString());
        if (session.length() == 0) session = "session";
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(startWallMs > 0 ? startWallMs : System.currentTimeMillis()));
        String filename = "mag_" + session + "_" + stamp + ".csv";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, filename);
        startActivityForResult(intent, CREATE_CSV_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CREATE_CSV_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                OutputStream out = getContentResolver().openOutputStream(data.getData());
                if (out == null) throw new Exception("Could not open output file");
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
                writer.write(buildCsvWithMetadata());
                writer.flush();
                writer.close();
                Toast.makeText(this, "CSV saved", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null || event.values == null || event.values.length < 3) return;
        int type = event.sensor.getType();
        if (type != Sensor.TYPE_MAGNETIC_FIELD && type != Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) return;
        if (firstSensorNs < 0) firstSensorNs = event.timestamp;
        double timeS = (event.timestamp - firstSensorNs) / 1_000_000_000.0;
        double dtMs = lastSensorNs > 0 ? (event.timestamp - lastSensorNs) / 1_000_000.0 : Double.NaN;
        lastSensorNs = event.timestamp;
        bx = event.values[0];
        by = event.values[1];
        bz = event.values[2];
        babs = (float) Math.sqrt(bx * bx + by * by + bz * bz);
        float biasX = event.values.length > 3 ? event.values[3] : Float.NaN;
        float biasY = event.values.length > 4 ? event.values[4] : Float.NaN;
        float biasZ = event.values.length > 5 ? event.values[5] : Float.NaN;
        if (recording) samples.add(new Sample(timeS, event.timestamp, bx, by, bz, babs, biasX, biasY, biasZ, accuracy, dtMs));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int acc) { accuracy = acc; }

    private void refreshUi() {
        int n = samples.size();
        double duration = firstSensorNs >= 0 && lastSensorNs > firstSensorNs ? (lastSensorNs - firstSensorNs) / 1_000_000_000.0 : 0.0;
        double fs = duration > 0 && n > 1 ? (n - 1) / duration : 0.0;
        valuesText.setText(String.format(Locale.US,
                "Bx: %.3f µT\nBy: %.3f µT\nBz: %.3f µT\n|B|: %.3f µT\nSamples: %d\nDuration: %.1f s\nEstimated fs: %.1f Hz\nAccuracy: %s",
                bx, by, bz, babs, n, duration, fs, accuracyText(accuracy)));
    }

    private String buildCsvWithMetadata() {
        StringBuilder sb = new StringBuilder(Math.max(4096, samples.size() * 110));
        sb.append("# MAG Recorder metadata\n");
        sb.append("# app_version,").append(VERSION).append("\n");
        sb.append("# subject_or_session,").append(csv(sessionEdit.getText().toString())).append("\n");
        sb.append("# notes,").append(csv(notesEdit.getText().toString())).append("\n");
        sb.append("# start_time_utc,").append(utc(startWallMs > 0 ? startWallMs : System.currentTimeMillis())).append("\n");
        sb.append("# device,").append(csv(Build.MANUFACTURER + " " + Build.MODEL)).append("\n");
        sb.append("# android_version,").append(csv(Build.VERSION.RELEASE)).append("\n");
        sb.append("# sensor_name,").append(csv(magSensor == null ? "none" : magSensor.getName())).append("\n");
        sb.append("# sensor_vendor,").append(csv(magSensor == null ? "none" : magSensor.getVendor())).append("\n");
        sb.append("# sensor_resolution_uT,").append(magSensor == null ? "" : magSensor.getResolution()).append("\n");
        sb.append("# sensor_maximum_range_uT,").append(magSensor == null ? "" : magSensor.getMaximumRange()).append("\n");
        sb.append("# requested_sample_period_us,").append(SAMPLE_PERIOD_US).append("\n");
        sb.append("# sample_count,").append(samples.size()).append("\n");
        sb.append("time_s,sensor_timestamp_ns,system_start_unix_ms,Bx_uT,By_uT,Bz_uT,B_abs_uT,bias_x_uT,bias_y_uT,bias_z_uT,accuracy,dt_ms\n");
        for (Sample s : samples) {
            sb.append(String.format(Locale.US, "%.9f,%d,%d,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%d,%.6f\n",
                    s.timeS, s.timestampNs, startWallMs, s.bx, s.by, s.bz, s.babs, s.biasX, s.biasY, s.biasZ, s.accuracy, s.dtMs));
        }
        return sb.toString();
    }

    private static String clean(String s) { return s == null ? "" : s.trim().replaceAll("[^A-Za-z0-9_.-]+", "_"); }
    private static String csv(String s) {
        if (s == null) return "";
        boolean needs = s.contains(",") || s.contains("\n") || s.contains("\"");
        String escaped = s.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ");
        return needs ? "\"" + escaped + "\"" : escaped;
    }
    private static String utc(long ms) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date(ms));
    }
    private static String accuracyText(int a) {
        if (a == SensorManager.SENSOR_STATUS_ACCURACY_HIGH) return "high";
        if (a == SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) return "medium";
        if (a == SensorManager.SENSOR_STATUS_ACCURACY_LOW) return "low";
        if (a == SensorManager.SENSOR_STATUS_UNRELIABLE) return "unreliable";
        return String.valueOf(a);
    }
    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(2), dp(8), dp(2), dp(8));
        return p;
    }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private static class Sample {
        final double timeS; final long timestampNs; final float bx, by, bz, babs, biasX, biasY, biasZ; final int accuracy; final double dtMs;
        Sample(double timeS, long timestampNs, float bx, float by, float bz, float babs, float biasX, float biasY, float biasZ, int accuracy, double dtMs) {
            this.timeS = timeS; this.timestampNs = timestampNs; this.bx = bx; this.by = by; this.bz = bz; this.babs = babs; this.biasX = biasX; this.biasY = biasY; this.biasZ = biasZ; this.accuracy = accuracy; this.dtMs = dtMs;
        }
    }
}
