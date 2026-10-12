package com.example.smartalbum

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MainActivity UI自动化测试
 * 测试APP启动、底部导航切换、基本功能
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MainActivityTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun appLaunch_shouldDisplayMapFragment() {
        // 验证APP启动后地图页面正常显示
        onView(withId(R.id.map_view))
            .check(matches(isDisplayed()))
    }

    @Test
    fun bottomNav_clickLocations_shouldSwitchFragment() {
        // 点击底部"位置"导航
        onView(withId(R.id.nav_locations))
            .perform(click())

        // 验证位置列表页面显示
        onView(withId(R.id.location_recycler_view))
            .check(matches(isDisplayed()))
    }

    @Test
    fun bottomNav_clickSettings_shouldSwitchFragment() {
        // 点击底部"设置"导航
        onView(withId(R.id.nav_settings))
            .perform(click())

        // 验证设置页面显示（检查设置页面的某个控件）
        onView(withId(R.id.btn_export))
            .check(matches(isDisplayed()))
    }

    @Test
    fun bottomNav_clickMap_shouldSwitchBackToMap() {
        // 先切换到位置页面
        onView(withId(R.id.nav_locations))
            .perform(click())

        // 再切换回地图页面
        onView(withId(R.id.nav_map))
            .perform(click())

        // 验证地图页面显示
        onView(withId(R.id.map_view))
            .check(matches(isDisplayed()))
    }

    @Test
    fun locationButton_click_shouldNotCrash() {
        // 点击定位按钮，验证不崩溃
        onView(withId(R.id.btn_location))
            .perform(click())

        // 等待定位完成
        Thread.sleep(3000)

        // 验证地图仍然显示
        onView(withId(R.id.map_view))
            .check(matches(isDisplayed()))
    }

    @Test
    fun appContext_shouldBeAvailable() {
        // 验证应用上下文可用
        val context = ApplicationProvider.getApplicationContext<Context>()
        assert(context.packageName == "com.example.smartalbum")
    }
}
