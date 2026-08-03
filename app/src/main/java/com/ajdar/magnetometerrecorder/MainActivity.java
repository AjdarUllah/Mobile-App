package com.ajdar.magnetometerrecorder;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("Pressure MAG Recorder v7.3");
        title.setTextSize(28);
        root.addView(title);
        TextView body = new TextView(this);
        body.setText("Pressure HR with SQI window markers, pressure peak events, and counted magnetometer breaths.");
        body.setTextSize(16);
        body.setPadding(0, dp(12), 0, dp(12));
        root.addView(body);
        Button open = new Button(this);
        open.setText("Open Health Recorder v7.3");
        root.addView(open);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, HealthRecorderActivity.class));
            }
        });
        setContentView(root);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}