package com.ajdar.magnetometerrecorder;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class MainActivity extends Activity implements SensorEventListener {
    private static final int MAX_PLOT_POINTS = 900;
    private static final int REQUESTED_SAMPLE_PERIOD_US = 5_000; // best effort ~200 Hz; actual rate is device-dependent.

    private SensorManager sensorManager;
    private Sensor magnetometer;
    private Sensor uncalibratedMagnetometer;
    private Sensor activeSensor;

    private TextView statusText;
    private TextView valueText;
    private TextView metaText;
    private EditText subjectInput;
    private EditText noteInput;
    private Button startButton;
    private Button stopButton;
    private Button exportButton;
    private MagPlotView plotView;

    private final ArrayDeque<MagSample> samples = new ArrayDeque<>();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean recording = false;
    private long firstSensorTimestampNs = -1L;
    private long firstWallClockMs = -1L;
    private long lastSensorTimestampNs = -1L;
    private int accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
    private float latestBx = Float.NaN;
    private float latestBy = Float.NaN;
    private float latestBz = Float.NaN;
    private float latestMagnitude = Float.NaN;
    private int droppedForPlot = 0;

    private final Runnable uiTicker = new Runnable() {
        @Override
        public void run() {
            updateReadout();
            uiHandler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        uncalibratedMagnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED);
        activeSensor = magnetometer != null ? magnetometer : uncalibratedMagnetometer;
        buildUi();
        describeSensor();
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiHandler.post(uiTicker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(uiTicker);
        if (!recording) {
            sensorManager.unregisterListener(this);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("MAG Recorder");
        title.setTextSize(26f);
        title.setTextColor(Color.rgb(17, 24, 39));
        title.setGravity(Gravity.START);
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("Phone magnetometer logger for Bx, By, Bz and |B| with phyphox-style CSV + JSON metadata export.");
        subtitle.setTextSize(14f);
        subtitle.setTextColor(Color.rgb(75, 85, 99));
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle, matchWrap());

        subjectInput = new EditText(this);
        subjectInput.setHint("Subject/session ID, e.g. S01_rest_supine");
        subjectInput.setSingleLine(true);
        subjectInput.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(subjectInput, matchWrap());

        noteInput = new EditText(this);
        noteInput.setHint("Notes: phone position, posture, reference device, breathing task");
        noteInput.setMinLines(2);
        noteInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        root.addView(noteInput, matchWrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        startButton = new Button(this);
        startButton.setText("Start");
        stopButton = new Button(this);
        stopButton.setText("Stop");
        stopButton.setEnabled(false);
        exportButton = new Button(this);
        exportButton.setText("Export");
        exportButton.setEnabled(false);
        row.addView(startButton, weighted());
        row.addView(stopButton, weighted());
        row.addView(exportButton, weighted());
        root.addView(row, matchWrap());

        statusText = new TextView(this);
        statusText.setTextSize(15f);
        statusText.setTextColor(Color.rgb(31, 41, 55));
        statusText.setPadding(0, dp(10), 0, dp(4));
        root.addView(statusText, matchWrap());

        valueText = new TextView(this);
        valueText.setTextSize(16f);
        valueText.setTextColor(Color.rgb(17, 24, 39));
        valueText.setPadding(0, dp(4), 0, dp(8));
        root.addView(valueText, matchWrap());

        plotView = new MagPlotView(this);
        root.addView(plotView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320)));

        metaText = new TextView(this);
        metaText.setTextSize(12f);
        metaText.setTextColor(Color.rgb(75, 85, 99));
        metaText.setPadding(0, dp(12), 0, 0);
        root.addView(metaText, matchWrap());

        setContentView(scroll);

        startButton.setOnClickListener(v -> startRecording());
        stopButton.setOnClickListener(v -> stopRecording());
        exportButton.setOnClickListener(v -> exportSession());
    }

    private void describeSensor() {
        if (activeSensor == null) {
            statusText.setText("No magnetometer found on this phone.");
            startButton.setEnabled(false);
            return;
        }
        String type = activeSensor.getType() == Sensor.TYPE_MAGNETIC_FIELD ? "calibrated" : "uncalibrated";
        String text = "Sensor: " + activeSensor.getName() + " (" + type + ")\n"
                + "Vendor: " + activeSensor.getVendor() + " | Resolution: " + activeSensor.getResolution() + " µT | Max: " + activeSensor.getMaximumRange() + " µT\n"
                + "Requested period: " + REQUESTED_SAMPLE_PERIOD_US + " µs; actual timestamps are saved per sample.";
        metaText.setText(text);
        statusText.setText("Ready. Place phone consistently; avoid metal objects, speakers and magnetic cases.");
    }

    private void startRecording() {
        if (activeSensor == null) return;
        samples.clear();
        plotView.clear();
        firstSensorTimestampNs = -1L;
        firstWallClockMs = System.currentTimeMillis();
        lastSensorTimestampNs = -1L;
        droppedForPlot = 0;
        recording = true;
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
        exportButton.setEnabled(false);
        subjectInput.setEnabled(false);
        noteInput.setEnabled(false);
        sensorManager.unregisterListener(this);
        boolean ok = sensorManager.registerListener(this, activeSensor, REQUESTED_SAMPLE_PERIOD_US, 0);
        if (!ok) {
            recording = false;
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
            subjectInput.setEnabled(true);
            noteInput.setEnabled(true);
            Toast.makeText(this, "Could not start magnetometer", Toast.LENGTH_LONG).show();
            return;
        }
        statusText.setText("Recording magnetometer...");
    }

    private void stopRecording() {
        recording = false;
        sensorManager.unregisterListener(this);
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        exportButton.setEnabled(!samples.isEmpty());
        subjectInput.setEnabled(true);
        noteInput.setEnabled(true);
        statusText.setText("Stopped. Samples: " + samples.size() + ". Export CSV + JSON metadata when ready.");
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_MAGNETIC_FIELD && event.sensor.getType() != Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) {
            return;
        }
        long ts = event.timestamp;
        if (firstSensorTimestampNs < 0) firstSensorTimestampNs = ts;
        double tSec = (ts - firstSensorTimestampNs) / 1_000_000_000.0;
        float bx = event.values.length > 0 ? event.values[0] : Float.NaN;
        float by = event.values.length > 1 ? event.values[1] : Float.NaN;
        float bz = event.values.length > 2 ? event.values[2] : Float.NaN;
        float bAbs = (float) Math.sqrt(bx * bx + by * by + bz * bz);
        float biasX = event.values.length > 3 ? event.values[3] : Float.NaN;
        float biasY = event.values.length > 4 ? event.values[4] : Float.NaN;
        float biasZ = event.values.length > 5 ? event.values[5] : Float.NaN;
        double dtMs = lastSensorTimestampNs > 0 ? (ts - lastSensorTimestampNs) / 1_000_000.0 : Double.NaN;
        lastSensorTimestampNs = ts;

        latestBx = bx;
        latestBy = by;
        latestBz = bz;
        latestMagnitude = bAbs;
        if (recording) {
            samples.add(new MagSample(tSec, ts, bx, by, bz, bAbs, biasX, biasY, biasZ, accuracy, dtMs));
        }
        plotView.add(tSec, bx, by, bz, bAbs);
        if (plotView.getPointCount() > MAX_PLOT_POINTS) droppedForPlot++;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int acc) {
        accuracy = acc;
    }

    private void updateReadout() {
        int n = samples.size();
        double duration = firstSensorTimestampNs >= 0 && lastSensorTimestampNs > firstSensorTimestampNs
                ? (lastSensorTimestampNs - firstSensorTimestampNs) / 1_000_000_000.0 : 0.0;
        double fs = duration > 0 && n > 1 ? (n - 1) / duration : Double.NaN;
        valueText.setText(String.format(Locale.US,
                "Bx %.3f µT   By %.3f µT   Bz %.3f µT   |B| %.3f µT\nSamples %d   Duration %.1f s   Estimated fs %.1f Hz   Accuracy %s",
                latestBx, latestBy, latestBz, latestMagnitude, n, duration, fs, accuracyLabel(accuracy)));
        if (recording) {
            statusText.setText("Recording... keep phone orientation/position fixed. Plot shows recent samples only; export keeps all samples.");
        }
    }

    private void exportSession() {
        if (samples.isEmpty()) {
            Toast.makeText(this, "No samples to export", Toast.LENGTH_SHORT).show();
            return;
        }
        String sid = sanitize(subjectInput.getText().toString().trim());
        if (sid.isEmpty()) sid = "session";
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(firstWallClockMs > 0 ? firstWallClockMs : System.currentTimeMillis()));
        String base = "mag_" + sid + "_" + stamp;
        try {
            writeTextToDownloads(base + ".csv", "text/csv", buildCsv());
            writeTextToDownloads(base + "_metadata.json", "application/json", buildMetadataJson(base));
            Toast.makeText(this, "Exported to Downloads/MAG_Recorder", Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String buildCsv() {
        StringBuilder sb = new StringBuilder(samples.size() * 96);
        sb.append("time_s,sensor_timestamp_ns,system_start_unix_ms,Bx_uT,By_uT,Bz_uT,B_abs_uT,bias_x_uT,bias_y_uT,bias_z_uT,accuracy,dt_ms\n");
        for (MagSample s : samples) {
            sb.append(String.format(Locale.US,
                    "%.9f,%d,%d,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%d,%.6f\n",
                    s.timeSec, s.sensorTimestampNs, firstWallClockMs, s.bx, s.by, s.bz, s.bAbs,
                    s.biasX, s.biasY, s.biasZ, s.accuracy, s.dtMs));
        }
        return sb.toString();
    }

    private String buildMetadataJson(String baseName) {
        double duration = firstSensorTimestampNs >= 0 && lastSensorTimestampNs > firstSensorTimestampNs
                ? (lastSensorTimestampNs - firstSensorTimestampNs) / 1_000_000_000.0 : 0.0;
        double fs = duration > 0 && samples.size() > 1 ? (samples.size() - 1) / duration : Double.NaN;
        String startIso = iso(firstWallClockMs > 0 ? firstWallClockMs : System.currentTimeMillis());
        return "{\n"
                + kv("file_base", baseName, true)
                + kv("app", "MAG Recorder", true)
                + kv("app_version", "0.1.0", true)
                + kv("subject_or_session", subjectInput.getText().toString().trim(), true)
                + kv("notes", noteInput.getText().toString().trim(), true)
                + kv("start_time_utc", startIso, true)
                + kv("device_manufacturer", Build.MANUFACTURER, true)
                + kv("device_model", Build.MODEL, true)
                + kv("android_version", Build.VERSION.RELEASE, true)
                + kv("sensor_type", activeSensor == null ? "none" : sensorTypeName(activeSensor), true)
                + kv("sensor_name", activeSensor == null ? "none" : activeSensor.getName(), true)
                + kv("sensor_vendor", activeSensor == null ? "none" : activeSensor.getVendor(), true)
                + kv("sensor_resolution_uT", activeSensor == null ? Double.NaN : activeSensor.getResolution(), true)
                + kv("sensor_maximum_range_uT", activeSensor == null ? Double.NaN : activeSensor.getMaximumRange(), true)
                + kv("requested_sample_period_us", REQUESTED_SAMPLE_PERIOD_US, true)
                + kv("sample_count", samples.size(), true)
                + kv("duration_s", duration, true)
                + kv("estimated_sampling_rate_hz", fs, true)
                + kv("columns", "time_s,sensor_timestamp_ns,system_start_unix_ms,Bx_uT,By_uT,Bz_uT,B_abs_uT,bias_x_uT,bias_y_uT,bias_z_uT,accuracy,dt_ms", false)
                + "}\n";
    }

    private String kv(String key, String value, boolean comma) {
        return "  \"" + escape(key) + "\": \"" + escape(value) + "\"" + (comma ? "," : "") + "\n";
    }

    private String kv(String key, double value, boolean comma) {
        String v = Double.isFinite(value) ? String.format(Locale.US, "%.9f", value) : "null";
        return "  \"" + escape(key) + "\": " + v + (comma ? "," : "") + "\n";
    }

    private String kv(String key, int value, boolean comma) {
        return "  \"" + escape(key) + "\": " + value + (comma ? "," : "") + "\n";
    }

    private void writeTextToDownloads(String fileName, String mime, String text) throws IOException {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MAG_Recorder");
        }
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Could not create export file");
        try (OutputStream out = resolver.openOutputStream(uri);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
            writer.write(text);
        }
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9_.-]+", "_").replaceAll("_+", "_");
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private static String iso(long unixMs) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date(unixMs));
    }

    private static String sensorTypeName(Sensor s) {
        if (s.getType() == Sensor.TYPE_MAGNETIC_FIELD) return "TYPE_MAGNETIC_FIELD_CALIBRATED";
        if (s.getType() == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) return "TYPE_MAGNETIC_FIELD_UNCALIBRATED";
        return String.valueOf(s.getType());
    }

    private static String accuracyLabel(int acc) {
        switch (acc) {
            case SensorManager.SENSOR_STATUS_ACCURACY_HIGH: return "high";
            case SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM: return "medium";
            case SensorManager.SENSOR_STATUS_ACCURACY_LOW: return "low";
            case SensorManager.SENSOR_STATUS_UNRELIABLE: return "unreliable";
            default: return String.valueOf(acc);
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), dp(6), dp(3), dp(6));
        return lp;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static class MagSample {
        final double timeSec;
        final long sensorTimestampNs;
        final float bx, by, bz, bAbs, biasX, biasY, biasZ;
        final int accuracy;
        final double dtMs;
        MagSample(double timeSec, long sensorTimestampNs, float bx, float by, float bz, float bAbs,
                  float biasX, float biasY, float biasZ, int accuracy, double dtMs) {
            this.timeSec = timeSec;
            this.sensorTimestampNs = sensorTimestampNs;
            this.bx = bx;
            this.by = by;
            this.bz = bz;
            this.bAbs = bAbs;
            this.biasX = biasX;
            this.biasY = biasY;
            this.biasZ = biasZ;
            this.accuracy = accuracy;
            this.dtMs = dtMs;
        }
    }

    public static class MagPlotView extends View {
        private final ArrayDeque<float[]> points = new ArrayDeque<>();
        private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint xPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint yPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint zPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public MagPlotView(Context context) {
            super(context);
            axisPaint.setColor(Color.rgb(31, 41, 55));
            axisPaint.setStrokeWidth(2f);
            gridPaint.setColor(Color.rgb(229, 231, 235));
            gridPaint.setStrokeWidth(1f);
            textPaint.setColor(Color.rgb(75, 85, 99));
            textPaint.setTextSize(28f);
            xPaint.setColor(Color.rgb(220, 38, 38));
            yPaint.setColor(Color.rgb(22, 163, 74));
            zPaint.setColor(Color.rgb(37, 99, 235));
            mPaint.setColor(Color.rgb(124, 58, 237));
            for (Paint p : new Paint[]{xPaint, yPaint, zPaint, mPaint}) p.setStrokeWidth(3f);
            setBackgroundColor(Color.WHITE);
        }

        public void add(double t, float bx, float by, float bz, float bAbs) {
            points.add(new float[]{(float) t, bx, by, bz, bAbs});
            while (points.size() > MAX_PLOT_POINTS) points.removeFirst();
            postInvalidateOnAnimation();
        }

        public void clear() {
            points.clear();
            invalidate();
        }

        public int getPointCount() {
            return points.size();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            int left = 56, right = 18, top = 28, bottom = 44;
            float x0 = left, x1 = w - right, y0 = top, y1 = h - bottom;
            canvas.drawRect(x0, y0, x1, y1, axisPaint);
            for (int i = 1; i < 4; i++) {
                float yy = y0 + i * (y1 - y0) / 4f;
                canvas.drawLine(x0, yy, x1, yy, gridPaint);
            }
            canvas.drawText("Bx red, By green, Bz blue, |B| purple (recent window)", x0, h - 12, textPaint);
            if (points.size() < 2) {
                canvas.drawText("Waiting for magnetometer samples...", x0 + 10, y0 + 45, textPaint);
                return;
            }
            float min = Float.POSITIVE_INFINITY;
            float max = Float.NEGATIVE_INFINITY;
            for (float[] p : points) {
                for (int i = 1; i <= 4; i++) {
                    if (Float.isFinite(p[i])) {
                        min = Math.min(min, p[i]);
                        max = Math.max(max, p[i]);
                    }
                }
            }
            if (!Float.isFinite(min) || !Float.isFinite(max) || Math.abs(max - min) < 1e-6f) {
                min = -1f; max = 1f;
            }
            float pad = 0.08f * (max - min);
            min -= pad; max += pad;
            canvas.drawText(String.format(Locale.US, "%.1f µT", max), 4, y0 + 10, textPaint);
            canvas.drawText(String.format(Locale.US, "%.1f µT", min), 4, y1, textPaint);
            drawSeries(canvas, x0, x1, y0, y1, min, max, 1, xPaint);
            drawSeries(canvas, x0, x1, y0, y1, min, max, 2, yPaint);
            drawSeries(canvas, x0, x1, y0, y1, min, max, 3, zPaint);
            drawSeries(canvas, x0, x1, y0, y1, min, max, 4, mPaint);
        }

        private void drawSeries(Canvas c, float x0, float x1, float y0, float y1, float min, float max, int idx, Paint paint) {
            int n = points.size();
            int i = 0;
            Float lastX = null, lastY = null;
            for (float[] p : points) {
                float v = p[idx];
                if (!Float.isFinite(v)) { i++; continue; }
                float x = x0 + (x1 - x0) * i / Math.max(1, n - 1);
                float y = y1 - (y1 - y0) * (v - min) / Math.max(1e-6f, max - min);
                if (lastX != null) c.drawLine(lastX, lastY, x, y, paint);
                lastX = x; lastY = y;
                i++;
            }
        }
    }
}
