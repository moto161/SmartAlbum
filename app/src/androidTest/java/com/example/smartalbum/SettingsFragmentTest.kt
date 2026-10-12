package com.example.smartalbum

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 设置页面UI自动化测试
 * 测试缓存管理、日志查看、关于等功能
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class SettingsFragmentTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Before
    fun setUp() {
        // 切换到设置页面
        onView(withId(R.id.nav_settings))
            .perform(click())
        Thread.sleep(1000)
    }

    @Test
    fun settings_shouldDisplayExportButton() {
        // 验证导出缓存按钮显示
        onView(withId(R.id.btn_export))
            .check(matches(isDisplayed()))
    }

    @Test
    fun settings_shouldDisplayImportButton() {
        // 验证导入缓存按钮显示
        onView(withId(R.id.btn_import))
            .check(matches(isDisplayed()))
    }

    @Test
    fun settings_shouldDisplayClearCacheButton() {
        // 验证清除缓存按钮显示
        onView(withId(R.id.btn_clear_cache))
            .check(matches(isDisplayed()))
    }

    @Test
    fun settings_shouldDisplayViewLogsButton() {
        // 验证查看日志按钮显示
        onView(withId(R.id.btn_view_logs))
            .check(matches(isDisplayed()))
    }

    @Test
    fun settings_shouldDisplayClearLogsButton() {
        // 验证清除日志按钮显示
        onView(withId(R.id.btn_clear_logs))
            .check(matches(isDisplayed()))
    }

    @Test
    fun settings_shouldDisplayAboutCard() {
        // 验证关于卡片显示
        onView(withId(R.id.card_about))
            .check(matches(isDisplayed()))
    }

    @Test
    fun settings_shouldDisplayAppVersion() {
        // 验证版本号显示
        onView(withId(R.id.tv_app_version))
            .check(matches(isDisplayed()))
    }

    @Test
    fun exportButton_click_shouldNotCrash() {
        // 点击导出按钮，验证不崩溃（会弹出文件选择器）
        onView(withId(R.id.btn_export))
            .perform(click())

        Thread.sleep(1000)

        // 按返回键关闭文件选择器
        pressBack()

        Thread.sleep(500)

        // 验证设置页面仍然显示
        onView(withId(R.id.btn_export))
            .check(matches(isDisplayed()))
    }

    @Test
    fun viewLogsButton_click_shouldNotCrash() {
        // 点击查看日志按钮，验证不崩溃
        onView(withId(R.id.btn_view_logs))
            .perform(click())

        Thread.sleep(1000)

        // 按返回键关闭日志对话框
        pressBack()

        Thread.sleep(500)

        // 验证设置页面仍然显示
        onView(withId(R.id.btn_view_logs))
            .check(matches(isDisplayed()))
    }

    @Test
    fun settings_navigateToMap_shouldSwitchFragment() {
        // 从设置切换到地图
        onView(withId(R.id.nav_map))
            .perform(click())

        Thread.sleep(500)

        // 验证地图页面显示
        onView(withId(R.id.map_view))
            .check(matches(isDisplayed()))
    }
}
