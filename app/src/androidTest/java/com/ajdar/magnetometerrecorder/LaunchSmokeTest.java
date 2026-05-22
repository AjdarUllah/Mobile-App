package com.ajdar.magnetometerrecorder;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LaunchSmokeTest {
    @Rule
    public ActivityScenarioRule<MainActivity> mainRule = new ActivityScenarioRule<>(MainActivity.class);

    @Test
    public void launcherOpens() {
        onView(withText("MAG Recorder")).check(matches(isDisplayed()));
        onView(withText("Open Magnetometer Recorder")).check(matches(isDisplayed()));
    }

    @Test
    public void recorderScreenOpensFromLauncher() {
        onView(withText("Open Magnetometer Recorder")).perform(click());
        onView(withText("Magnetometer Recorder")).check(matches(isDisplayed()));
        onView(withText("Start")).check(matches(isDisplayed()));
    }
}
