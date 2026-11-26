package com.example.autohmi

import android.app.ActivityOptions
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry

/** AAOS also has an inactive cluster display; every UI fixture must use the intended screen. */
internal inline fun <reified A : ComponentActivity> createDisplayComposeRule(): AndroidComposeTestRule<ActivityScenarioRule<A>, A> {
    val displayId = InstrumentationRegistry.getArguments().getString("displayId")?.toIntOrNull()
        ?: Display.DEFAULT_DISPLAY
    require(displayId >= 0)
    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
    val launcher = ActivityScenarioRule(A::class.java, options)
    return AndroidComposeTestRule(activityRule = launcher, activityProvider = { rule ->
        var host: A? = null
        rule.scenario.onActivity { activity ->
            check(activity.window.decorView.display?.displayId == displayId) { "UI fixture launched on the wrong display" }
            host = activity
        }
        checkNotNull(host)
    })
}

internal fun createDisplayContentRule() = createDisplayComposeRule<ComponentActivity>()
