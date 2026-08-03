package com.ajdar.magnetometerrecorder;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class HealthRecorderActivity extends Activity implements SensorEventListener {
    private static final int SAVE_ZIP_REQUEST = 7001;
    private static final String VERSION = "7.1";

    private SensorManager sensorManager;
    private Sensor magSensor;
    private Sensor pressureSensor;
    private Sensor linearAccSensor;
    private Sensor gyroSensor;

    private TextView statusText;
    private TextView liveValuesText;
    private TextView sensorMetaText;
    private TextView derivedSummaryText;
    private EditText sessionEdit;
    private EditText notesEdit;
    private EditText hzBox;
    private Spinner frequencyMode;
    private Button startButton;
    private Button stopButton;
    private Button discardButton;
    private Button saveTopButton;
    private Button saveExportButton;
    private Button magTabButton;
    private Button pressureTabButton;
    private Button accTabButton;
    private Button gyroTabButton;
    private Button derivedTabButton;
    private Button sensorsTabButton;
    private Button exportTabButton;
    private FrameLayout tabArea;
    private LinearLayout magTab;
    private LinearLayout pressureTab;
    private LinearLayout accTab;
    private LinearLayout gyroTab;
    private LinearLayout derivedTab;
    private LinearLayout sensorsTab;
    private LinearLayout exportTab;

    private PlotView bxPlot;
    private PlotView byPlot;
    private PlotView bzPlot;
    private PlotView bAbsPlot;
    private PlotView pressurePlot;
    private PlotView axPlot;
    private PlotView ayPlot;
    private PlotView azPlot;
    private PlotView gxPlot;
    private PlotView gyPlot;
    private PlotView gzPlot;
    private PlotView pressureHrBeforeSqiPlot;
    private PlotView pressureHrAfterSqiPlot;
    private PlotView respirationPlot;
    private PlotView respirationWavePlot;

    private final ArrayList<Row> rows = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean recording = false;
    private boolean analyzing = false;
    private long firstSensorNs = -1L;
    private long lastMagNs = -1L;
    private long lastPressureNs = -1L;
    private long lastAccNs = -1L;
    private long lastGyroNs = -1L;
    private long wallStartMs = -1L;

    private float bx = Float.NaN;
    private float by = Float.NaN;
    private float bz = Float.NaN;
    private float bAbs = Float.NaN;
    private float pressureHpa = Float.NaN;
    private float ax = Float.NaN;
    private float ay = Float.NaN;
    private float az = Float.NaN;
    private float gx = Float.NaN;
    private float gy = Float.NaN;
    private float gz = Float.NaN;
    private int magAccuracy = 0;
    private int pressureAccuracy = 0;
    private int accAccuracy = 0;
    private int gyroAccuracy = 0;

    private DerivedAnalysis.AnalysisResult analysis;
    private boolean uiReady = false;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateLiveValues();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        try {
            buildUi();
            uiReady = true;
        } catch (Throwable throwable) {
            showStartupError(throwable);
        }
    }

    private void showStartupError(Throwable throwable) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("Recorder startup failed");
        title.setTextSize(24);
        root.addView(title);
        TextView body = new TextView(this);
        body.setText(throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        body.setTextSize(16);
        body.setPadding(0, dp(12), 0, dp(12));
        root.addView(body);
        Button oldRecorder = new Button(this);
        oldRecorder.setText("Open old v6 recorder");
        oldRecorder.setOnClickListener(v -> startActivity(new Intent(this, PlotV51Activity.class)));
        root.addView(oldRecorder);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(ticker);
        if (uiReady) handler.post(ticker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        if (!recording && sensorManager != null) sensorManager.unregisterListener(this);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(246, 248, 252));
        root.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView title = tv("Pressure + MAG Recorder v7.1", 25, Color.rgb(15, 23, 42));
        root.addView(title);
        root.addView(tv("Records phyphox-style pressure and magnetometer files, then computes pressure HR and magnetometer respiration after Stop.", 13, Color.rgb(71, 85, 105)));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(8), dp(8), dp(8), dp(8));
        top.setBackgroundColor(Color.WHITE);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout frequencyRow = new LinearLayout(this);
        frequencyRow.setOrientation(LinearLayout.HORIZONTAL);
        frequencyRow.setGravity(Gravity.CENTER_VERTICAL);
        frequencyRow.addView(tv("Frequency", 13, Color.rgb(51, 65, 85)));
        frequencyMode = new Spinner(this);
        String[] modes = {"Manual Hz", "Automatic maximum"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, modes);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        frequencyMode.setAdapter(adapter);
        frequencyRow.addView(frequencyMode, new LinearLayout.LayoutParams(0, dp(48), 1));
        hzBox = new EditText(this);
        hzBox.setText("200");
        hzBox.setSingleLine(true);
        hzBox.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        frequencyRow.addView(hzBox, new LinearLayout.LayoutParams(dp(90), dp(48)));
        frequencyRow.addView(tv("Hz", 13, Color.rgb(51, 65, 85)));
        top.addView(frequencyRow);

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        startButton = new Button(this);
        startButton.setText("START");
        stopButton = new Button(this);
        stopButton.setText("STOP");
        stopButton.setEnabled(false);
        discardButton = new Button(this);
        discardButton.setText("DISCARD");
        discardButton.setEnabled(false);
        saveTopButton = new Button(this);
        saveTopButton.setText("SAVE ZIP");
        saveTopButton.setEnabled(false);
        buttonRow.addView(startButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        buttonRow.addView(stopButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        buttonRow.addView(discardButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        buttonRow.addView(saveTopButton, new LinearLayout.LayoutParams(0, dp(52), 1));
        top.addView(buttonRow);

        statusText = tv("Ready. Pressure HR and respiration are computed after Stop.", 14, Color.rgb(51, 65, 85));
        top.addView(statusText);

        HorizontalScrollView horizontalScroll = new HorizontalScrollView(this);
        horizontalScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        magTabButton = tab("MAG");
        pressureTabButton = tab("Pressure");
        accTabButton = tab("ACC");
        gyroTabButton = tab("GYRO");
        derivedTabButton = tab("Derived");
        sensorsTabButton = tab("Sensors");
        exportTabButton = tab("Export");
        Button[] buttons = {magTabButton, pressureTabButton, accTabButton, gyroTabButton, derivedTabButton, sensorsTabButton, exportTabButton};
        for (Button b : buttons) tabs.addView(b, new LinearLayout.LayoutParams(dp(104), dp(44)));
        horizontalScroll.addView(tabs);
        root.addView(horizontalScroll);

        tabArea = new FrameLayout(this);
        root.addView(tabArea, new LinearLayout.LayoutParams(-1, 0, 1));

        buildMagTab();
        buildPressureTab();
        buildAccTab();
        buildGyroTab();
        buildDerivedTab();
        buildSensorsTab();
        buildExportTab();

        tabArea.addView(magTab);
        tabArea.addView(pressureTab);
        tabArea.addView(accTab);
        tabArea.addView(gyroTab);
        tabArea.addView(derivedTab);
        tabArea.addView(sensorsTab);
        tabArea.addView(exportTab);
        setContentView(root);

        magTabButton.setOnClickListener(v -> showTab(0));
        pressureTabButton.setOnClickListener(v -> showTab(1));
        accTabButton.setOnClickListener(v -> showTab(2));
        gyroTabButton.setOnClickListener(v -> showTab(3));
        derivedTabButton.setOnClickListener(v -> showTab(4));
        sensorsTabButton.setOnClickListener(v -> showTab(5));
        exportTabButton.setOnClickListener(v -> showTab(6));
        startButton.setOnClickListener(v -> startRecording());
        stopButton.setOnClickListener(v -> stopRecording());
        discardButton.setOnClickListener(v -> askDiscard());
        saveTopButton.setOnClickListener(v -> saveZip());
        frequencyMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                hzBox.setEnabled(position == 0 && !recording);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        showTab(0);
    }

    private void buildMagTab() {
        LinearLayout content = tabContent();
        content.addView(tv("Magnetometer", 18, Color.rgb(15, 23, 42)));
        content.addView(tv("Raw magnetic field. Tap any plot for zoom.", 13, Color.rgb(71, 85, 105)));
        bxPlot = new PlotView(this, "Bx vs time", "Bx (uT)", Color.rgb(190, 32, 38));
        byPlot = new PlotView(this, "By vs time", "By (uT)", Color.rgb(0, 135, 70));
        bzPlot = new PlotView(this, "Bz vs time", "Bz (uT)", Color.rgb(30, 92, 180));
        bAbsPlot = new PlotView(this, "|B| vs time", "|B| (uT)", Color.rgb(120, 48, 150));
        content.addView(bxPlot, plotSize());
        content.addView(byPlot, plotSize());
        content.addView(bzPlot, plotSize());
        content.addView(bAbsPlot, plotSize());
        magTab = wrap(content);
    }

    private void buildPressureTab() {
        LinearLayout content = tabContent();
        content.addView(tv("Barometer Pressure", 18, Color.rgb(15, 23, 42)));
        content.addView(tv("Raw pressure is exported as Pressure.csv with a meta/time.csv file.", 13, Color.rgb(71, 85, 105)));
        pressurePlot = new PlotView(this, "Pressure vs time", "Pressure (hPa)", Color.rgb(2, 132, 199));
        content.addView(pressurePlot, plotSize());
        pressureTab = wrap(content);
    }

    private void buildAccTab() {
        LinearLayout content = tabContent();
        content.addView(tv("Linear Acceleration", 18, Color.rgb(15, 23, 42)));
        axPlot = new PlotView(this, "Ax vs time", "Ax (m/s2)", Color.rgb(190, 32, 38));
        ayPlot = new PlotView(this, "Ay vs time", "Ay (m/s2)", Color.rgb(0, 135, 70));
        azPlot = new PlotView(this, "Az vs time", "Az (m/s2)", Color.rgb(30, 92, 180));
        content.addView(axPlot, plotSize());
        content.addView(ayPlot, plotSize());
        content.addView(azPlot, plotSize());
        accTab = wrap(content);
    }

    private void buildGyroTab() {
        LinearLayout content = tabContent();
        content.addView(tv("Gyroscope", 18, Color.rgb(15, 23, 42)));
        gxPlot = new PlotView(this, "Gx vs time", "Gx (rad/s)", Color.rgb(190, 32, 38));
        gyPlot = new PlotView(this, "Gy vs time", "Gy (rad/s)", Color.rgb(0, 135, 70));
        gzPlot = new PlotView(this, "Gz vs time", "Gz (rad/s)", Color.rgb(30, 92, 180));
        content.addView(gxPlot, plotSize());
        content.addView(gyPlot, plotSize());
        content.addView(gzPlot, plotSize());
        gyroTab = wrap(content);
    }

    private void buildDerivedTab() {
        LinearLayout content = tabContent();
        content.addView(tv("Derived Signals", 18, Color.rgb(15, 23, 42)));
        derivedSummaryText = tv("Stop a recording to compute pressure HR and magnetometer respiration.", 14, Color.rgb(51, 65, 85));
        content.addView(derivedSummaryText);
        pressureHrBeforeSqiPlot = new PlotView(this, "Pressure HR before SQI", "HR (bpm)", Color.rgb(37, 99, 235));
        pressureHrAfterSqiPlot = new PlotView(this, "Pressure HR after SQI", "HR (bpm)", Color.rgb(22, 163, 74));
        respirationPlot = new PlotView(this, "Magnetometer respiration", "breaths/min", Color.rgb(5, 150, 105));
        respirationWavePlot = new PlotView(this, "Filtered respiration waveform", "z(By)", Color.rgb(124, 58, 237));
        content.addView(pressureHrBeforeSqiPlot, plotSize());
        content.addView(pressureHrAfterSqiPlot, plotSize());
        content.addView(respirationPlot, plotSize());
        content.addView(respirationWavePlot, plotSize());
        derivedTab = wrap(content);
    }

    private void buildSensorsTab() {
        LinearLayout content = tabContent();
        content.addView(tv("Live Sensor Values", 18, Color.rgb(15, 23, 42)));
        liveValuesText = tv("Waiting for samples...", 16, Color.rgb(30, 41, 59));
        content.addView(liveValuesText);
        sensorMetaText = tv("App version: " + VERSION, 13, Color.rgb(71, 85, 105));
        content.addView(sensorMetaText);
        sensorsTab = wrap(content);
    }

    private void buildExportTab() {
        LinearLayout content = tabContent();
        content.addView(tv("Export + Metadata", 18, Color.rgb(15, 23, 42)));
        sessionEdit = new EditText(this);
        sessionEdit.setHint("Session ID, e.g. session_30_20260803_143000");
        sessionEdit.setSingleLine(true);
        content.addView(sessionEdit);
        notesEdit = new EditText(this);
        notesEdit.setHint("Notes: phone position, posture, reference device");
        notesEdit.setMinLines(3);
        notesEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        content.addView(notesEdit);
        content.addView(tv("The ZIP contains Pressure/Pressure.csv, Magnetometer 1/Magnetometer.csv, matching meta/time.csv files, the raw event table, and derived CSVs.", 13, Color.rgb(71, 85, 105)));
        saveExportButton = new Button(this);
        saveExportButton.setText("SAVE PHYPHOX-STYLE ZIP");
        saveExportButton.setEnabled(false);
        content.addView(saveExportButton, new LinearLayout.LayoutParams(-1, dp(56)));
        saveExportButton.setOnClickListener(v -> saveZip());
        exportTab = wrap(content);
    }

    private void showTab(int index) {
        LinearLayout[] panels = {magTab, pressureTab, accTab, gyroTab, derivedTab, sensorsTab, exportTab};
        Button[] buttons = {magTabButton, pressureTabButton, accTabButton, gyroTabButton, derivedTabButton, sensorsTabButton, exportTabButton};
        for (int i = 0; i < panels.length; i++) {
            panels[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            buttons[i].setEnabled(i != index);
        }
    }

    private boolean setupSensors() {
        if (sensorManager != null && magSensor != null && pressureSensor != null) return true;
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager == null) {
            statusText.setText("Sensor service unavailable.");
            return false;
        }
        magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (magSensor == null) magSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED);
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        linearAccSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        sensorMetaText.setText(sensorDescription());
        if (magSensor == null) {
            statusText.setText("No magnetometer found on this phone.");
            return false;
        }
        if (pressureSensor == null) {
            statusText.setText("No pressure/barometer sensor found on this phone.");
            return false;
        }
        return true;
    }

    private String sensorDescription() {
        return "App version: " + VERSION
                + "\nPhone: " + Build.MANUFACTURER + " " + Build.MODEL
                + " | Android " + Build.VERSION.RELEASE
                + "\nMAG: " + sensorName(magSensor)
                + "\nPressure: " + sensorName(pressureSensor)
                + "\nACC: " + sensorName(linearAccSensor)
                + "\nGYRO: " + sensorName(gyroSensor)
                + "\nFrequency mode: " + frequencyText();
    }

    private String sensorName(Sensor sensor) {
        return sensor == null ? "not available" : sensor.getName();
    }

    private int requestedDelayUs() {
        if (frequencyMode.getSelectedItemPosition() == 1) return SensorManager.SENSOR_DELAY_FASTEST;
        float hz = 200f;
        try {
            hz = Float.parseFloat(hzBox.getText().toString());
        } catch (Exception ignored) {}
        if (hz < 1f) hz = 1f;
        if (hz > 1000f) hz = 1000f;
        return Math.max(1, (int) (1_000_000f / hz));
    }

    private String frequencyText() {
        return frequencyMode.getSelectedItemPosition() == 1
                ? "Automatic maximum"
                : "Manual " + hzBox.getText().toString() + " Hz";
    }

    private void startRecording() {
        if (!setupSensors()) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(hzBox.getWindowToken(), 0);
        clearData();
        firstSensorNs = -1L;
        lastMagNs = -1L;
        lastPressureNs = -1L;
        lastAccNs = -1L;
        lastGyroNs = -1L;
        wallStartMs = System.currentTimeMillis();
        setRecordingControls(true);
        int delay = requestedDelayUs();
        String request = "mode=" + frequencyText() + ", delay_us=" + delay;
        try {
            sensorManager.unregisterListener(this);
            boolean okMag = sensorManager.registerListener(this, magSensor, delay);
            boolean okPressure = sensorManager.registerListener(this, pressureSensor, delay);
            boolean okAcc = linearAccSensor == null || sensorManager.registerListener(this, linearAccSensor, delay);
            boolean okGyro = gyroSensor == null || sensorManager.registerListener(this, gyroSensor, delay);
            if (!okMag) {
                failStart("registerListener returned false for magnetometer; " + request);
                return;
            }
            if (!okPressure) {
                failStart("registerListener returned false for pressure sensor; " + request);
                return;
            }
            if (!okAcc) {
                failStart("registerListener returned false for linear acceleration; " + request);
                return;
            }
            if (!okGyro) {
                failStart("registerListener returned false for gyroscope; " + request);
                return;
            }
        } catch (SecurityException ex) {
            failStart("SecurityException: " + ex.getMessage() + ". Check HIGH_SAMPLING_RATE_SENSORS permission; " + request);
            return;
        } catch (Exception ex) {
            failStart(ex.getClass().getSimpleName() + ": " + ex.getMessage() + "; " + request);
            return;
        }
        recording = true;
        statusText.setText("Recording pressure + magnetometer. Derived HR/respiration will compute after Stop.");
    }

    private void stopRecording() {
        recording = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        setRecordingControls(false);
        boolean hasRows = !rows.isEmpty();
        discardButton.setEnabled(hasRows);
        saveTopButton.setEnabled(false);
        saveExportButton.setEnabled(false);
        statusText.setText("Stopped. Events: " + rows.size() + ". Computing derived signals...");
        computeDerivedAsync();
    }

    private void setRecordingControls(boolean active) {
        startButton.setEnabled(!active);
        stopButton.setEnabled(active);
        discardButton.setEnabled(active || !rows.isEmpty());
        saveTopButton.setEnabled(!active && !rows.isEmpty() && !analyzing);
        saveExportButton.setEnabled(!active && !rows.isEmpty() && !analyzing);
        hzBox.setEnabled(!active && frequencyMode.getSelectedItemPosition() == 0);
        frequencyMode.setEnabled(!active);
    }

    private void failStart(String message) {
        recording = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        setRecordingControls(false);
        discardButton.setEnabled(false);
        statusText.setText("Start failed: " + message);
        new AlertDialog.Builder(this).setTitle("Recording failed").setMessage(message).setPositiveButton("OK", null).show();
    }

    private void askDiscard() {
        if (rows.isEmpty() && !recording) {
            statusText.setText("Nothing to discard.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Discard recording?")
                .setMessage("This clears the current unsaved samples, plots, HR, and respiration results.")
                .setPositiveButton("Discard", (dialog, which) -> discardNow())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void discardNow() {
        recording = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        clearData();
        setRecordingControls(false);
        discardButton.setEnabled(false);
        saveTopButton.setEnabled(false);
        saveExportButton.setEnabled(false);
        statusText.setText("Discarded current recording. Ready for a new session.");
    }

    private void clearData() {
        rows.clear();
        analysis = null;
        bxPlot.clear();
        byPlot.clear();
        bzPlot.clear();
        bAbsPlot.clear();
        pressurePlot.clear();
        axPlot.clear();
        ayPlot.clear();
        azPlot.clear();
        gxPlot.clear();
        gyPlot.clear();
        gzPlot.clear();
        pressureHrBeforeSqiPlot.clear();
        pressureHrAfterSqiPlot.clear();
        respirationPlot.clear();
        respirationWavePlot.clear();
        derivedSummaryText.setText("Stop a recording to compute pressure HR and magnetometer respiration.");
        derivedTabButton.setText("Derived");
        derivedTabButton.setTextColor(Color.rgb(15, 23, 42));
        derivedTabButton.setBackgroundColor(Color.TRANSPARENT);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.sensor == null || event.values == null) return;
        int type = event.sensor.getType();
        float time = timeSeconds(event.timestamp);
        if (type == Sensor.TYPE_PRESSURE) {
            float dt = lastPressureNs > 0 ? (event.timestamp - lastPressureNs) / 1_000_000f : Float.NaN;
            lastPressureNs = event.timestamp;
            pressureHpa = event.values[0];
            if (recording) {
                rows.add(Row.pressure(time, event.timestamp, pressureHpa, magAccuracy, pressureAccuracy, accAccuracy, gyroAccuracy, dt));
                pressurePlot.add(time, pressureHpa);
            }
            return;
        }
        if (type == Sensor.TYPE_LINEAR_ACCELERATION && event.values.length >= 3) {
            float dt = lastAccNs > 0 ? (event.timestamp - lastAccNs) / 1_000_000f : Float.NaN;
            lastAccNs = event.timestamp;
            ax = event.values[0];
            ay = event.values[1];
            az = event.values[2];
            if (recording) {
                rows.add(Row.acc(time, event.timestamp, ax, ay, az, magAccuracy, pressureAccuracy, accAccuracy, gyroAccuracy, dt));
                axPlot.add(time, ax);
                ayPlot.add(time, ay);
                azPlot.add(time, az);
            }
            return;
        }
        if (type == Sensor.TYPE_GYROSCOPE && event.values.length >= 3) {
            float dt = lastGyroNs > 0 ? (event.timestamp - lastGyroNs) / 1_000_000f : Float.NaN;
            lastGyroNs = event.timestamp;
            gx = event.values[0];
            gy = event.values[1];
            gz = event.values[2];
            if (recording) {
                rows.add(Row.gyro(time, event.timestamp, gx, gy, gz, magAccuracy, pressureAccuracy, accAccuracy, gyroAccuracy, dt));
                gxPlot.add(time, gx);
                gyPlot.add(time, gy);
                gzPlot.add(time, gz);
            }
            return;
        }
        if ((type == Sensor.TYPE_MAGNETIC_FIELD || type == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) && event.values.length >= 3) {
            float dt = lastMagNs > 0 ? (event.timestamp - lastMagNs) / 1_000_000f : Float.NaN;
            lastMagNs = event.timestamp;
            bx = event.values[0];
            by = event.values[1];
            bz = event.values[2];
            bAbs = (float) Math.sqrt(bx * bx + by * by + bz * bz);
            if (recording) {
                rows.add(Row.mag(time, event.timestamp, bx, by, bz, bAbs, magAccuracy, pressureAccuracy, accAccuracy, gyroAccuracy, dt));
                bxPlot.add(time, bx);
                byPlot.add(time, by);
                bzPlot.add(time, bz);
                bAbsPlot.add(time, bAbs);
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor == null) return;
        int type = sensor.getType();
        if (type == Sensor.TYPE_PRESSURE) pressureAccuracy = accuracy;
        else if (type == Sensor.TYPE_LINEAR_ACCELERATION) accAccuracy = accuracy;
        else if (type == Sensor.TYPE_GYROSCOPE) gyroAccuracy = accuracy;
        else if (type == Sensor.TYPE_MAGNETIC_FIELD || type == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) magAccuracy = accuracy;
    }

    private float timeSeconds(long timestampNs) {
        if (firstSensorNs < 0) firstSensorNs = timestampNs;
        return (timestampNs - firstSensorNs) / 1_000_000_000f;
    }

    private void updateLiveValues() {
        if (!uiReady || liveValuesText == null) return;
        float duration = firstSensorNs >= 0 ? (SystemClock.elapsedRealtimeNanos() - firstSensorNs) / 1_000_000_000f : 0f;
        liveValuesText.setText(String.format(Locale.US,
                "Pressure\n%.3f hPa\n\nMAG\nBx %.2f  By %.2f  Bz %.2f  |B| %.2f uT\n\nLinear ACC without gravity\nAx %.3f  Ay %.3f  Az %.3f m/s2\n\nGYRO\nGx %.3f  Gy %.3f  Gz %.3f rad/s\n\nEvents %d   Duration %.1f s   Frequency %s",
                pressureHpa, bx, by, bz, bAbs, ax, ay, az, gx, gy, gz, rows.size(), duration, frequencyText()));
    }

    private void computeDerivedAsync() {
        if (analyzing) return;
        final ArrayList<Row> snapshot = new ArrayList<>(rows);
        if (snapshot.isEmpty()) {
            statusText.setText("Stopped. No samples captured.");
            return;
        }
        analyzing = true;
        derivedTabButton.setText("Computing");
        derivedTabButton.setTextColor(Color.rgb(15, 23, 42));
        derivedTabButton.setBackgroundColor(Color.rgb(253, 224, 71));
        setRecordingControls(false);
        final String recordingName = clean(sessionEdit.getText().toString()).isEmpty() ? "recording" : clean(sessionEdit.getText().toString());
        new Thread(() -> {
            DerivedAnalysis.AnalysisResult computed = buildAnalysis(recordingName, snapshot);
            runOnUiThread(() -> {
                analysis = computed;
                analyzing = false;
                renderAnalysis(computed);
                setRecordingControls(false);
                statusText.setText(computed.pressureMessage + " " + computed.respirationMessage);
            });
        }).start();
    }

    private DerivedAnalysis.AnalysisResult buildAnalysis(String recordingName, ArrayList<Row> source) {
        ArrayList<DerivedAnalysis.PressurePoint> pressure = new ArrayList<>();
        ArrayList<DerivedAnalysis.MagPoint> mag = new ArrayList<>();
        for (Row row : source) {
            if ("pressure".equals(row.type) && finite(row.pressureHpa)) {
                pressure.add(new DerivedAnalysis.PressurePoint(row.t, row.pressureHpa));
            } else if ("mag".equals(row.type) && finite(row.bx) && finite(row.by) && finite(row.bz)) {
                mag.add(new DerivedAnalysis.MagPoint(row.t, row.bx, row.by, row.bz, row.bAbs));
            }
        }
        return DerivedAnalysis.analyze(recordingName, pressure, mag);
    }

    private void renderAnalysis(DerivedAnalysis.AnalysisResult result) {
        pressureHrBeforeSqiPlot.clear();
        pressureHrAfterSqiPlot.clear();
        respirationPlot.clear();
        respirationWavePlot.clear();
        int hrCount = 0;
        int hrAfterSqiCount = 0;
        int pressureAbove85Count = 0;
        int pressureLowSqiCount = 0;
        for (DerivedAnalysis.HrWindow window : result.pressureHr) {
            if (finite(window.finalHrBpm)) {
                pressureHrBeforeSqiPlot.add((float) window.centerS, (float) window.finalHrBpm);
                hrCount++;
            }
            if (finite(window.hrAfterSqiBpm)) {
                pressureHrAfterSqiPlot.add((float) window.centerS, (float) window.hrAfterSqiBpm);
                hrAfterSqiCount++;
            }
            if ("unreliable_above_85_bpm".equals(window.pressureReliability)) pressureAbove85Count++;
            if ("unreliable_low_sqi".equals(window.pressureReliability)) pressureLowSqiCount++;
        }
        int respCount = 0;
        for (DerivedAnalysis.RespWindow window : result.respiration) {
            if (finite(window.respirationRateBpm)) {
                respirationPlot.add((float) window.centerS, (float) window.respirationRateBpm);
                respCount++;
            }
        }
        addDownsampled(respirationWavePlot, result.respirationWaveTime, result.respirationWave, 1200);
        derivedSummaryText.setText(String.format(Locale.US,
                "%s\n%s\n\nPressure before SQI: %d windows\nPressure after SQI: %d reliable windows\nPressure unreliable above 85 bpm: %d windows\nPressure unreliable low SQI: %d windows\n\nNative fs estimate: pressure %.1f Hz, magnetometer %.1f Hz\nPressure coverage: %.0f%%\nExport will include raw phyphox-style files and derived CSVs.",
                result.pressureMessage,
                result.respirationMessage,
                hrCount,
                hrAfterSqiCount,
                pressureAbove85Count,
                pressureLowSqiCount,
                result.pressureNativeFs,
                result.magNativeFs,
                100.0 * result.pressureCoverage));
        if (hrAfterSqiCount > 0 && respCount > 0) {
            derivedTabButton.setText("Derived OK");
            derivedTabButton.setTextColor(Color.WHITE);
            derivedTabButton.setBackgroundColor(Color.rgb(22, 163, 74));
        } else {
            derivedTabButton.setText("Derived Check");
            derivedTabButton.setTextColor(Color.rgb(15, 23, 42));
            derivedTabButton.setBackgroundColor(Color.rgb(251, 191, 36));
        }
    }

    private void addDownsampled(PlotView plot, double[] t, double[] x, int maxPoints) {
        if (t == null || x == null || t.length == 0 || x.length == 0) return;
        int n = Math.min(t.length, x.length);
        int step = Math.max(1, n / Math.max(1, maxPoints));
        for (int i = 0; i < n; i += step) {
            if (finite(x[i])) plot.add((float) t[i], (float) x[i]);
        }
    }

    private void saveZip() {
        if (rows.isEmpty()) {
            Toast.makeText(this, "No samples to save", Toast.LENGTH_SHORT).show();
            return;
        }
        if (analyzing) {
            Toast.makeText(this, "Still computing derived signals", Toast.LENGTH_SHORT).show();
            return;
        }
        if (analysis == null) analysis = buildAnalysis(clean(sessionEdit.getText().toString()), new ArrayList<>(rows));
        String id = clean(sessionEdit.getText().toString());
        if (id.isEmpty()) id = "session";
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(wallStartMs > 0 ? wallStartMs : System.currentTimeMillis()));
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, "phyphox_plus_v71_" + id + "_" + stamp + ".zip");
        startActivityForResult(intent, SAVE_ZIP_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SAVE_ZIP_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                OutputStream out = getContentResolver().openOutputStream(data.getData());
                if (out == null) throw new Exception("Could not open output file");
                ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
                writeZip(zip);
                zip.close();
                Toast.makeText(this, "ZIP saved", Toast.LENGTH_LONG).show();
            } catch (Exception ex) {
                Toast.makeText(this, "Save failed: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void writeZip(ZipOutputStream zip) throws Exception {
        String session = clean(sessionEdit.getText().toString());
        if (session.isEmpty()) session = "recording";
        putText(zip, "README_export.txt", exportReadme());
        putText(zip, "Pressure/Pressure.csv", pressureCsv());
        putText(zip, "Pressure/meta/time.csv", metaTimeCsv(recordingDurationS()));
        putText(zip, "Magnetometer 1/Magnetometer.csv", magnetometerCsv());
        putText(zip, "Magnetometer 1/meta/time.csv", metaTimeCsv(recordingDurationS()));
        putText(zip, "raw/EventTable.csv", eventTableCsv());
        putText(zip, "derived/pressure_hr.csv", pressureHrCsv(session));
        putText(zip, "derived/magnetometer_respiration_track.csv", respirationTrackCsv(session));
        putText(zip, "derived/magnetometer_respiration_per_minute.csv", respirationMinuteCsv(session));
        putText(zip, "derived/magnetometer_respiration_peaks.csv", respirationPeaksCsv(session));
        putText(zip, "derived/summary.csv", summaryCsv(session));
    }

    private void putText(ZipOutputStream zip, String path, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
        writer.write(text);
        writer.flush();
        zip.closeEntry();
    }

    private String exportReadme() {
        return "Pressure + MAG Recorder v" + VERSION + "\n"
                + "\nRaw compatibility files:\n"
                + "- Pressure/Pressure.csv\n"
                + "- Pressure/meta/time.csv\n"
                + "- Magnetometer 1/Magnetometer.csv\n"
                + "- Magnetometer 1/meta/time.csv\n"
                + "\nDerived files:\n"
                + "- derived/pressure_hr.csv: 10-second pressure-derived HR windows, pressure SQI, before-SQI HR, after-SQI HR, and above-85 reliability flags\n"
                + "- derived/magnetometer_respiration_track.csv: rolling respiration-rate track\n"
                + "- derived/magnetometer_respiration_per_minute.csv: per-minute breath rate/count bins\n"
                + "- derived/magnetometer_respiration_peaks.csv: detected breath peak times\n"
                + "- derived/summary.csv: one-row export summary\n"
                + "\nThe raw files are intentionally kept separate from derived outputs so existing phyphox-style analysis scripts can keep reading column 1 as time and column 2 as sensor value.\n";
    }

    private String pressureCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("Time (s),Pressure (hPa)\n");
        for (Row row : rows) {
            if ("pressure".equals(row.type)) {
                sb.append(fmt(row.t)).append(',').append(fmt(row.pressureHpa)).append('\n');
            }
        }
        return sb.toString();
    }

    private String magnetometerCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("Time (s),Magnetic field x (uT),Magnetic field y (uT),Magnetic field z (uT),Absolute field (uT)\n");
        for (Row row : rows) {
            if ("mag".equals(row.type)) {
                sb.append(fmt(row.t)).append(',')
                        .append(fmt(row.bx)).append(',')
                        .append(fmt(row.by)).append(',')
                        .append(fmt(row.bz)).append(',')
                        .append(fmt(row.bAbs)).append('\n');
            }
        }
        return sb.toString();
    }

    private String metaTimeCsv(double durationS) {
        long startMs = wallStartMs > 0 ? wallStartMs : System.currentTimeMillis();
        long stopMs = startMs + Math.round(durationS * 1000.0);
        StringBuilder sb = new StringBuilder();
        sb.append("event,experiment time (s),system time,system time text\n");
        sb.append("START,0.000000,").append(fmt(startMs / 1000.0)).append(',').append(csv(utc(startMs))).append('\n');
        sb.append("STOP,").append(fmt(durationS)).append(',').append(fmt(stopMs / 1000.0)).append(',').append(csv(utc(stopMs))).append('\n');
        return sb.toString();
    }

    private String eventTableCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("# app_version,").append(VERSION).append('\n');
        sb.append("# export_style,phyphox_raw_zip_plus_derived_csv\n");
        sb.append("# frequency_mode,").append(csv(frequencyText())).append('\n');
        sb.append("# requested_delay_us,").append(requestedDelayUs()).append('\n');
        sb.append("# subject,").append(csv(sessionEdit.getText().toString())).append('\n');
        sb.append("# notes,").append(csv(notesEdit.getText().toString())).append('\n');
        sb.append("# device,").append(csv(Build.MANUFACTURER + " " + Build.MODEL)).append('\n');
        sb.append("# event_count,").append(rows.size()).append('\n');
        sb.append("sensor_type,time_s,timestamp_ns,system_start_unix_ms,pressure_hPa,Bx_uT,By_uT,Bz_uT,B_abs_uT,linacc_x_mps2,linacc_y_mps2,linacc_z_mps2,gyro_x_rad_s,gyro_y_rad_s,gyro_z_rad_s,mag_accuracy,pressure_accuracy,linacc_accuracy,gyro_accuracy,dt_ms\n");
        for (Row row : rows) {
            sb.append(row.type).append(',')
                    .append(fmt(row.t)).append(',')
                    .append(row.ns).append(',')
                    .append(wallStartMs).append(',')
                    .append(fmt(row.pressureHpa)).append(',')
                    .append(fmt(row.bx)).append(',')
                    .append(fmt(row.by)).append(',')
                    .append(fmt(row.bz)).append(',')
                    .append(fmt(row.bAbs)).append(',')
                    .append(fmt(row.ax)).append(',')
                    .append(fmt(row.ay)).append(',')
                    .append(fmt(row.az)).append(',')
                    .append(fmt(row.gx)).append(',')
                    .append(fmt(row.gy)).append(',')
                    .append(fmt(row.gz)).append(',')
                    .append(row.magAccuracy).append(',')
                    .append(row.pressureAccuracy).append(',')
                    .append(row.accAccuracy).append(',')
                    .append(row.gyroAccuracy).append(',')
                    .append(fmt(row.dtMs)).append('\n');
        }
        return sb.toString();
    }

    private String pressureHrCsv(String session) {
        StringBuilder sb = new StringBuilder();
        sb.append("recording,center_s,window_start_s,window_end_s,raw_fused_hr_bpm,final_hr_bpm,pressure_sqi,pressure_hr_after_sqi_bpm,pressure_reliability_status,dominant_family,selector_reason,selector_key,peak_hr_bpm,acf_hr_bpm,spectral_hr_bpm,confidence\n");
        if (analysis != null) {
            for (DerivedAnalysis.HrWindow w : analysis.pressureHr) {
                sb.append(csv(session)).append(',')
                        .append(fmt(w.centerS)).append(',')
                        .append(fmt(w.startS)).append(',')
                        .append(fmt(w.endS)).append(',')
                        .append(fmt(w.rawHrBpm)).append(',')
                        .append(fmt(w.finalHrBpm)).append(',')
                        .append(fmt(w.pressureSqi)).append(',')
                        .append(fmt(w.hrAfterSqiBpm)).append(',')
                        .append(csv(w.pressureReliability)).append(',')
                        .append(csv(w.dominantFamily)).append(',')
                        .append(csv(w.selectorReason)).append(',')
                        .append(DerivedAnalysis.PRESSURE_SELECTOR_KEY).append(',')
                        .append(fmt(w.peakHrBpm)).append(',')
                        .append(fmt(w.acfHrBpm)).append(',')
                        .append(fmt(w.spectralHrBpm)).append(',')
                        .append(fmt(w.confidence)).append('\n');
            }
        }
        return sb.toString();
    }

    private String respirationTrackCsv(String session) {
        StringBuilder sb = new StringBuilder();
        sb.append("recording,center_s,window_start_s,window_end_s,respiration_rate_bpm,breath_count_rate_bpm,breath_count,invalid_fraction,method\n");
        if (analysis != null) {
            for (DerivedAnalysis.RespWindow w : analysis.respiration) {
                sb.append(csv(session)).append(',')
                        .append(fmt(w.centerS)).append(',')
                        .append(fmt(w.startS)).append(',')
                        .append(fmt(w.endS)).append(',')
                        .append(fmt(w.respirationRateBpm)).append(',')
                        .append(fmt(w.breathCountRateBpm)).append(',')
                        .append(w.breathCount).append(',')
                        .append(fmt(w.invalidFraction)).append(',')
                        .append(DerivedAnalysis.RESP_SELECTOR_KEY).append('\n');
            }
        }
        return sb.toString();
    }

    private String respirationMinuteCsv(String session) {
        StringBuilder sb = new StringBuilder();
        sb.append("recording,center_s,window_start_s,window_end_s,respiration_rate_bpm,breath_count_rate_bpm,breath_count,invalid_fraction,method\n");
        if (analysis != null) {
            for (DerivedAnalysis.RespWindow w : analysis.respirationMinuteBins) {
                sb.append(csv(session)).append(',')
                        .append(fmt(w.centerS)).append(',')
                        .append(fmt(w.startS)).append(',')
                        .append(fmt(w.endS)).append(',')
                        .append(fmt(w.respirationRateBpm)).append(',')
                        .append(fmt(w.breathCountRateBpm)).append(',')
                        .append(w.breathCount).append(',')
                        .append(fmt(w.invalidFraction)).append(',')
                        .append(DerivedAnalysis.RESP_SELECTOR_KEY).append('\n');
            }
        }
        return sb.toString();
    }

    private String respirationPeaksCsv(String session) {
        StringBuilder sb = new StringBuilder();
        sb.append("recording,breath_peak_time_s,method\n");
        if (analysis != null) {
            for (Double t : analysis.respirationPeakTimes) {
                sb.append(csv(session)).append(',').append(fmt(t)).append(',').append(DerivedAnalysis.RESP_SELECTOR_KEY).append('\n');
            }
        }
        return sb.toString();
    }

    private String summaryCsv(String session) {
        StringBuilder sb = new StringBuilder();
        sb.append("recording,app_version,event_count,duration_s,pressure_samples,magnetometer_samples,pressure_native_fs_hz,mag_native_fs_hz,pressure_median_hr_bpm,pressure_coverage,pressure_reliable_windows,pressure_unreliable_above_85_windows,pressure_unreliable_low_sqi_windows,respiration_median_bpm,pressure_message,respiration_message\n");
        sb.append(csv(session)).append(',')
                .append(VERSION).append(',')
                .append(rows.size()).append(',')
                .append(fmt(recordingDurationS())).append(',')
                .append(countType("pressure")).append(',')
                .append(countType("mag")).append(',');
        if (analysis != null) {
            int reliable = countPressureReliability("reliable");
            int above85 = countPressureReliability("unreliable_above_85_bpm");
            int lowSqi = countPressureReliability("unreliable_low_sqi");
            sb.append(fmt(analysis.pressureNativeFs)).append(',')
                    .append(fmt(analysis.magNativeFs)).append(',')
                    .append(fmt(analysis.pressureMedianHrBpm)).append(',')
                    .append(fmt(analysis.pressureCoverage)).append(',')
                    .append(reliable).append(',')
                    .append(above85).append(',')
                    .append(lowSqi).append(',')
                    .append(fmt(analysis.respirationMedianBpm)).append(',')
                    .append(csv(analysis.pressureMessage)).append(',')
                    .append(csv(analysis.respirationMessage)).append('\n');
        } else {
            sb.append("NaN,NaN,NaN,NaN,0,0,0,NaN,,\n");
        }
        return sb.toString();
    }

    private int countPressureReliability(String reliability) {
        if (analysis == null) return 0;
        int count = 0;
        for (DerivedAnalysis.HrWindow window : analysis.pressureHr) {
            if (reliability.equals(window.pressureReliability)) count++;
        }
        return count;
    }
    private int countType(String type) {
        int count = 0;
        for (Row row : rows) if (type.equals(row.type)) count++;
        return count;
    }

    private double recordingDurationS() {
        double max = 0.0;
        for (Row row : rows) if (finite(row.t)) max = Math.max(max, row.t);
        return max;
    }

    private TextView tv(String text, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setPadding(0, dp(3), 0, dp(3));
        return view;
    }

    private Button tab(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        return button;
    }

    private LinearLayout tabContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, dp(12));
        return content;
    }

    private LinearLayout wrap(LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        return panel;
    }

    private LinearLayout.LayoutParams plotSize() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(240));
        params.setMargins(0, dp(8), 0, dp(8));
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim().replaceAll("[^A-Za-z0-9_.-]+", "_");
    }

    private static String csv(String text) {
        if (text == null) return "";
        boolean quote = text.contains(",") || text.contains("\n") || text.contains("\"");
        String escaped = text.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ");
        return quote ? "\"" + escaped + "\"" : escaped;
    }

    private static String fmt(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? "NaN" : String.format(Locale.US, "%.9f", value);
    }

    private static String utc(long ms) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z' 'UTC'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(ms));
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static final class Row {
        final String type;
        final float t;
        final long ns;
        final float pressureHpa;
        final float bx;
        final float by;
        final float bz;
        final float bAbs;
        final float ax;
        final float ay;
        final float az;
        final float gx;
        final float gy;
        final float gz;
        final int magAccuracy;
        final int pressureAccuracy;
        final int accAccuracy;
        final int gyroAccuracy;
        final float dtMs;

        private Row(String type, float t, long ns, float pressureHpa, float bx, float by, float bz, float bAbs,
                    float ax, float ay, float az, float gx, float gy, float gz,
                    int magAccuracy, int pressureAccuracy, int accAccuracy, int gyroAccuracy, float dtMs) {
            this.type = type;
            this.t = t;
            this.ns = ns;
            this.pressureHpa = pressureHpa;
            this.bx = bx;
            this.by = by;
            this.bz = bz;
            this.bAbs = bAbs;
            this.ax = ax;
            this.ay = ay;
            this.az = az;
            this.gx = gx;
            this.gy = gy;
            this.gz = gz;
            this.magAccuracy = magAccuracy;
            this.pressureAccuracy = pressureAccuracy;
            this.accAccuracy = accAccuracy;
            this.gyroAccuracy = gyroAccuracy;
            this.dtMs = dtMs;
        }

        static Row pressure(float t, long ns, float pressureHpa, int ma, int pa, int aa, int ga, float dt) {
            return new Row("pressure", t, ns, pressureHpa, Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                    Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, ma, pa, aa, ga, dt);
        }

        static Row mag(float t, long ns, float bx, float by, float bz, float bAbs, int ma, int pa, int aa, int ga, float dt) {
            return new Row("mag", t, ns, Float.NaN, bx, by, bz, bAbs,
                    Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, ma, pa, aa, ga, dt);
        }

        static Row acc(float t, long ns, float ax, float ay, float az, int ma, int pa, int aa, int ga, float dt) {
            return new Row("acc", t, ns, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                    ax, ay, az, Float.NaN, Float.NaN, Float.NaN, ma, pa, aa, ga, dt);
        }

        static Row gyro(float t, long ns, float gx, float gy, float gz, int ma, int pa, int aa, int ga, float dt) {
            return new Row("gyro", t, ns, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN,
                    Float.NaN, Float.NaN, Float.NaN, gx, gy, gz, ma, pa, aa, ga, dt);
        }
    }

    public static class PlotView extends View {
        private static final int MAX_POINTS = 1200;
        private final ArrayDeque<float[]> points = new ArrayDeque<>();
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String title;
        private final String yLabel;
        private float zoom = 1f;
        private boolean popupEnabled = true;

        public PlotView(Context context, String title, String yLabel, int color) {
            super(context);
            this.title = title;
            this.yLabel = yLabel;
            backgroundPaint.setColor(Color.WHITE);
            borderPaint.setColor(Color.rgb(75, 85, 99));
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(2f);
            gridPaint.setColor(Color.rgb(226, 232, 240));
            textPaint.setColor(Color.rgb(55, 65, 81));
            textPaint.setTextSize(22f);
            linePaint.setColor(color);
            linePaint.setStrokeWidth(3f);
            linePaint.setStyle(Paint.Style.STROKE);
            setBackgroundColor(Color.rgb(246, 248, 252));
            setOnClickListener(v -> {
                if (popupEnabled) showPopup();
            });
        }

        public void clear() {
            points.clear();
            invalidate();
        }

        public void add(float t, float value) {
            points.add(new float[] {t, value});
            while (points.size() > MAX_POINTS) points.removeFirst();
            postInvalidateOnAnimation();
        }

        private void showPopup() {
            Dialog dialog = new Dialog(getContext());
            LinearLayout box = new LinearLayout(getContext());
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(18, 12, 18, 12);
            TextView label = new TextView(getContext());
            label.setText(title + "  |  Zoom: 1x");
            label.setTextSize(18);
            box.addView(label);
            PlotView big = new PlotView(getContext(), title, yLabel, linePaint.getColor());
            big.popupEnabled = false;
            big.points.addAll(points);
            box.addView(big, new LinearLayout.LayoutParams(-1, 620));
            SeekBar seekBar = new SeekBar(getContext());
            seekBar.setMax(9);
            seekBar.setProgress(0);
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    big.zoom = 1f + progress;
                    label.setText(title + "  |  Zoom: " + (progress + 1) + "x");
                    big.invalidate();
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {}

                @Override
                public void onStopTrackingTouch(SeekBar bar) {}
            });
            box.addView(seekBar);
            Button close = new Button(getContext());
            close.setText("Close");
            close.setOnClickListener(v -> dialog.dismiss());
            box.addView(close);
            dialog.setContentView(box);
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            float left = 86f;
            float top = 38f;
            float right = width - 22f;
            float bottom = height - 68f;
            canvas.drawRect(0, 0, width, height, backgroundPaint);
            canvas.drawText(title, left, 27f, textPaint);
            canvas.drawRect(left, top, right, bottom, borderPaint);
            for (int i = 1; i < 4; i++) {
                float y = top + i * (bottom - top) / 4f;
                canvas.drawLine(left, y, right, y, gridPaint);
            }
            canvas.drawText(yLabel, 6f, top + 18f, textPaint);
            if (points.size() < 2) {
                String xLabel = "Time (s)";
                canvas.drawText(xLabel, (left + right - textPaint.measureText(xLabel)) / 2f, height - 10f, textPaint);
                canvas.drawText("Waiting for samples...", left + 12f, top + 48f, textPaint);
                return;
            }
            float fullStart = points.peekFirst()[0];
            float endT = points.peekLast()[0];
            if (endT <= fullStart) endT = fullStart + 1f;
            float span = (endT - fullStart) / Math.max(1f, zoom);
            float startT = endT - span;
            float min = Float.POSITIVE_INFINITY;
            float max = Float.NEGATIVE_INFINITY;
            for (float[] point : points) {
                if (point[0] < startT) continue;
                float value = point[1];
                if (!Float.isNaN(value) && !Float.isInfinite(value)) {
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
            }
            if (min == Float.POSITIVE_INFINITY) {
                min = -1f;
                max = 1f;
            }
            if (Math.abs(max - min) < 1e-6f) {
                min -= 1f;
                max += 1f;
            }
            float pad = 0.1f * (max - min);
            min -= pad;
            max += pad;
            canvas.drawText(String.format(Locale.US, "%.1f", max), 6f, top + 38f, textPaint);
            canvas.drawText(String.format(Locale.US, "%.1f", min), 6f, bottom, textPaint);
            String leftLabel = String.format(Locale.US, "%.1fs", startT);
            String rightLabel = String.format(Locale.US, "%.1fs", endT);
            String xLabel = "Time (s)";
            canvas.drawText(leftLabel, left, height - 34f, textPaint);
            canvas.drawText(rightLabel, right - textPaint.measureText(rightLabel), height - 34f, textPaint);
            canvas.drawText(xLabel, (left + right - textPaint.measureText(xLabel)) / 2f, height - 10f, textPaint);
            Float lastX = null;
            Float lastY = null;
            for (float[] point : points) {
                if (point[0] < startT) continue;
                if (Float.isNaN(point[1]) || Float.isInfinite(point[1])) continue;
                float x = left + (right - left) * (point[0] - startT) / Math.max(1e-6f, endT - startT);
                float y = bottom - (bottom - top) * (point[1] - min) / Math.max(1e-6f, max - min);
                if (lastX != null) canvas.drawLine(lastX, lastY, x, y, linePaint);
                lastX = x;
                lastY = y;
            }
        }
    }
}
