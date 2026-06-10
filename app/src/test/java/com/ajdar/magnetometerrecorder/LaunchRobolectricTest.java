package com.ajdar.magnetometerrecorder;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.widget.Button;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;

@RunWith(RobolectricTestRunner.class)
public class LaunchRobolectricTest {
    @Test
    public void mainActivityLaunchesWithoutSensorAccess() {
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        assertNotNull(activity);
        assertTrue(activity.hasWindowFocus() || activity.getWindow() != null);
    }

    @Test
    public void recorderActivityLaunchesWithoutStartingSensor() {
        ActivityController<RecorderActivity> controller = Robolectric.buildActivity(RecorderActivity.class).setup();
        RecorderActivity activity = controller.get();
        assertNotNull(activity);
        assertTrue(activity.getWindow() != null);
    }
}
