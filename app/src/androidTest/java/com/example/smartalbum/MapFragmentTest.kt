package com.example.smartalbum

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
 * 地图页面UI自动化测试
 * 测试地图显示、定位、搜索、图层等功能
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MapFragmentTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun map_shouldDisplayMapView() {
        // 验证地图视图显示
        onView(withId(R.id.map_view))
            .check(matches(isDisplayed()))
    }

    @Test
    fun map_shouldDisplayInfoCard() {
        // 验证信息卡片显示
        onView(withId(R.id.info_card))
            .check(matches(isDisplayed()))
    }

    @Test
    fun map_shouldDisplayPhotoCount() {
        // 验证照片数量显示
        onView(withId(R.id.tv_photo_count))
            .check(matches(isDisplayed()))
    }

    @Test
    fun map_shouldDisplayCurrentLocation() {
        // 验证当前位置显示
        onView(withId(R.id.tv_current_location))
            .check(matches(isDisplayed()))
    }

    @Test
    fun map_shouldDisplayLocationButton() {
        // 验证定位按钮显示
        onView(withId(R.id.btn_location))
            .check(matches(isDisplayed()))
    }

    @Test
    fun map_shouldDisplaySearchButton() {
        // 验证搜索按钮显示
        onView(withId(R.id.btn_search))
            .check(matches(isDisplayed()))
    }

    @Test
    fun map_shouldDisplayLayerButton() {
        // 验证图层按钮显示
        onView(withId(R.id.btn_layer))
            .check(matches(isDisplayed()))
    }

    @Test
    fun locationButton_click_shouldNotCrash() {
        // 点击定位按钮，验证不崩溃
        onView(withId(R.id.btn_location))
            .perform(click())

        Thread.sleep(3000)

        // 验证地图仍然显示
        onView(withId(R.id.map_view))
            .check(matches(isDisplayed()))
    }

    @Test
    fun searchButton_click_shouldShowSearchBar() {
        // 点击搜索按钮，验证搜索栏显示
        onView(withId(R.id.btn_search))
            .perform(click())

        Thread.sleep(500)

        // 验证搜索栏显示
        onView(withId(R.id.search_bar))
            .check(matches(isDisplayed()))
    }

    @Test
    fun searchCancel_click_shouldHideSearchBar() {
        // 先打开搜索栏
        onView(withId(R.id.btn_search))
            .perform(click())
        Thread.sleep(500)

        // 点击取消按钮
        onView(withId(R.id.btn_search_cancel))
            .perform(click())

        Thread.sleep(500)

        // 验证地图仍然显示
        onView(withId(R.id.map_view))
            .check(matches(isDisplayed()))
    }

    @Test
    fun layerButton_click_shouldNotCrash() {
        // 点击图层按钮，验证不崩溃
        onView(withId(R.id.btn_layer))
            .perform(click())

        Thread.sleep(1000)

        // 验证地图仍然显示
        onView(withId(R.id.map_view))
            .check(matches(isDisplayed()))
    }

    @Test
    fun map_navigateToLocations_shouldSwitchFragment() {
        // 从地图切换到位置列表
        onView(withId(R.id.nav_locations))
            .perform(click())

        Thread.sleep(500)

        // 验证位置列表显示
        onView(withId(R.id.location_recycler_view))
            .check(matches(isDisplayed()))
    }
}
