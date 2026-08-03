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
        onView(withText("Pressure MAG Recorder v7.2")).check(matches(isDisplayed()));
        onView(withText("Open Health Recorder v7.2")).check(matches(isDisplayed()));
    }

    @Test
    public void recorderScreenOpensFromLauncher() {
        onView(withText("Open Health Recorder v7.2")).perform(click());
        onView(withText("Pressure + MAG Recorder v7.2")).check(matches(isDisplayed()));
        onView(withText("START")).check(matches(isDisplayed()));
    }
}
