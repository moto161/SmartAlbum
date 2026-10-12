package com.example.smartalbum

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.typeText
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
 * 位置列表页UI自动化测试
 * 测试搜索、排序、列表显示等功能
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class LocationListFragmentTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Before
    fun setUp() {
        // 切换到位置列表页
        onView(withId(R.id.nav_locations))
            .perform(click())
        Thread.sleep(1000)
    }

    @Test
    fun locationList_shouldDisplayToolbar() {
        // 验证工具栏显示
        onView(withId(R.id.toolbar))
            .check(matches(isDisplayed()))
    }

    @Test
    fun locationList_shouldDisplaySearchBox() {
        // 验证搜索框显示
        onView(withId(R.id.search_edit_text))
            .check(matches(isDisplayed()))
    }

    @Test
    fun locationList_shouldDisplaySortButton() {
        // 验证排序按钮显示
        onView(withId(R.id.btn_sort))
            .check(matches(isDisplayed()))
    }

    @Test
    fun locationList_shouldDisplayRecyclerView() {
        // 验证列表显示
        onView(withId(R.id.location_recycler_view))
            .check(matches(isDisplayed()))
    }

    @Test
    fun locationList_shouldDisplayStats() {
        // 验证统计信息显示
        onView(withId(R.id.tv_total_photos))
            .check(matches(isDisplayed()))
        onView(withId(R.id.tv_total_locations))
            .check(matches(isDisplayed()))
    }

    @Test
    fun searchBox_typeText_shouldNotCrash() {
        // 在搜索框输入文字，验证不崩溃
        onView(withId(R.id.search_edit_text))
            .perform(typeText("test"))

        Thread.sleep(500)

        // 验证列表仍然显示
        onView(withId(R.id.location_recycler_view))
            .check(matches(isDisplayed()))
    }

    @Test
    fun sortButton_click_shouldNotCrash() {
        // 点击排序按钮，验证不崩溃
        onView(withId(R.id.btn_sort))
            .perform(click())

        Thread.sleep(500)

        // 验证页面仍然显示
        onView(withId(R.id.location_recycler_view))
            .check(matches(isDisplayed()))
    }

    @Test
    fun locationList_navigateToMap_shouldSwitchFragment() {
        // 从位置列表切换到地图
        onView(withId(R.id.nav_map))
            .perform(click())

        Thread.sleep(500)

        // 验证地图页面显示
        onView(withId(R.id.map_view))
            .check(matches(isDisplayed()))
    }
}
