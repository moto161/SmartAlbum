package com.example.smartalbum.ui
import com.example.smartalbum.ui.common.StateLayoutHelper

import android.view.View
import android.widget.TextView
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * StateLayoutHelper单元测试
 * 测试四种状态切换：加载、空、错误、内容
 */
class StateLayoutHelperTest {

    private lateinit var loadingLayout: View
    private lateinit var emptyLayout: View
    private lateinit var errorLayout: View
    private lateinit var contentLayout: View
    private lateinit var loadingText: TextView
    private lateinit var emptyText: TextView
    private lateinit var errorText: TextView
    private lateinit var retryButton: View
    private lateinit var stateHelper: StateLayoutHelper

    @Before
    fun setUp() {
        loadingLayout = mockk(relaxed = true)
        emptyLayout = mockk(relaxed = true)
        errorLayout = mockk(relaxed = true)
        contentLayout = mockk(relaxed = true)
        loadingText = mockk(relaxed = true)
        emptyText = mockk(relaxed = true)
        errorText = mockk(relaxed = true)
        retryButton = mockk(relaxed = true)

        stateHelper = StateLayoutHelper(
            loadingLayout = loadingLayout,
            emptyLayout = emptyLayout,
            errorLayout = errorLayout,
            contentLayout = contentLayout,
            loadingText = loadingText,
            emptyText = emptyText,
            errorText = errorText,
            errorRetryButton = retryButton
        )
    }

    @Test
    fun `初始状态为CONTENT`() {
        assertEquals(StateLayoutHelper.State.CONTENT, stateHelper.currentState)
        assertTrue(stateHelper.isContent())
        assertFalse(stateHelper.isLoading())
    }

    @Test
    fun `showLoading切换到加载状态`() {
        stateHelper.showLoading("正在加载")
        assertEquals(StateLayoutHelper.State.LOADING, stateHelper.currentState)
        assertTrue(stateHelper.isLoading())
        verify { loadingLayout.visibility = View.VISIBLE }
        verify { emptyLayout.visibility = View.GONE }
        verify { errorLayout.visibility = View.GONE }
        verify { contentLayout.visibility = View.GONE }
    }

    @Test
    fun `showLoading设置文本`() {
        stateHelper.showLoading("扫描中...")
        verify { loadingText.text = "扫描中..." }
    }

    @Test
    fun `showEmpty切换到空状态`() {
        stateHelper.showEmpty("暂无数据")
        assertEquals(StateLayoutHelper.State.EMPTY, stateHelper.currentState)
        verify { loadingLayout.visibility = View.GONE }
        verify { emptyLayout.visibility = View.VISIBLE }
        verify { errorLayout.visibility = View.GONE }
        verify { contentLayout.visibility = View.GONE }
    }

    @Test
    fun `showEmpty设置文本`() {
        stateHelper.showEmpty("没有照片")
        verify { emptyText.text = "没有照片" }
    }

    @Test
    fun `showError切换到错误状态`() {
        stateHelper.showError("加载失败")
        assertEquals(StateLayoutHelper.State.ERROR, stateHelper.currentState)
        verify { loadingLayout.visibility = View.GONE }
        verify { emptyLayout.visibility = View.GONE }
        verify { errorLayout.visibility = View.VISIBLE }
        verify { contentLayout.visibility = View.GONE }
    }

    @Test
    fun `showError设置文本`() {
        stateHelper.showError("网络错误")
        verify { errorText.text = "网络错误" }
    }

    @Test
    fun `showError设置重试按钮点击`() {
        var retryCalled = false
        stateHelper.showError("失败", "重试") { retryCalled = true }
        verify { retryButton.setOnClickListener(any()) }
    }

    @Test
    fun `showContent切换到内容状态`() {
        stateHelper.showLoading()
        stateHelper.showContent()
        assertEquals(StateLayoutHelper.State.CONTENT, stateHelper.currentState)
        assertTrue(stateHelper.isContent())
        verify { loadingLayout.visibility = View.GONE }
        verify { emptyLayout.visibility = View.GONE }
        verify { errorLayout.visibility = View.GONE }
        verify { contentLayout.visibility = View.VISIBLE }
    }

    @Test
    fun `updateLoadingText更新文本`() {
        stateHelper.showLoading()
        stateHelper.updateLoadingText("已扫描 50/100")
        verify { loadingText.text = "已扫描 50/100" }
    }

    @Test
    fun `状态切换顺序正确`() {
        stateHelper.showLoading()
        assertEquals(StateLayoutHelper.State.LOADING, stateHelper.currentState)

        stateHelper.showEmpty()
        assertEquals(StateLayoutHelper.State.EMPTY, stateHelper.currentState)

        stateHelper.showError()
        assertEquals(StateLayoutHelper.State.ERROR, stateHelper.currentState)

        stateHelper.showContent()
        assertEquals(StateLayoutHelper.State.CONTENT, stateHelper.currentState)
    }

    @Test
    fun `空参数不崩溃`() {
        val helper = StateLayoutHelper()
        helper.showLoading()
        helper.showEmpty()
        helper.showError()
        helper.showContent()
        helper.updateLoadingText("test")
        assertTrue(helper.isContent())
    }
}
