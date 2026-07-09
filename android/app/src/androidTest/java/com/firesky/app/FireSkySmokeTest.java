package com.firesky.app;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withClassName;
import static org.hamcrest.Matchers.containsString;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Basic release-gate check: the Capacitor activity launches with its WebView. */
@RunWith(AndroidJUnit4.class)
public class FireSkySmokeTest {
    @Test
    public void launchesWithVisibleWebView() {
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            onView(withClassName(containsString("WebView"))).check(matches(isDisplayed()));
        }
    }
}
