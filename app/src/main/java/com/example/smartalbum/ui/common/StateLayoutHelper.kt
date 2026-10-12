package com.example.smartalbum.ui.common

import android.view.View
import android.widget.TextView
import androidx.annotation.StringRes

/**
 * 统一状态布局管理器
 * 封装加载中、空状态、错误状态、内容状态四种状态的切换逻辑
 */
class StateLayoutHelper(
    private val loadingLayout: View? = null,
    private val emptyLayout: View? = null,
    private val errorLayout: View? = null,
    private val contentLayout: View? = null,
    private val loadingText: TextView? = null,
    private val emptyText: TextView? = null,
    private val errorText: TextView? = null,
    private val errorRetryButton: View? = null
) {
    enum class State { LOADING, EMPTY, ERROR, CONTENT }

    var currentState: State = State.CONTENT
        private set

    fun showLoading(text: CharSequence? = null) {
        currentState = State.LOADING
        loadingText?.text = text
        loadingLayout?.visibility = View.VISIBLE
        emptyLayout?.visibility = View.GONE
        errorLayout?.visibility = View.GONE
        contentLayout?.visibility = View.GONE
    }

    fun showLoading(@StringRes textRes: Int) {
        loadingText?.setText(textRes)
        showLoading()
    }

    fun showEmpty(text: CharSequence? = null) {
        currentState = State.EMPTY
        emptyText?.text = text
        loadingLayout?.visibility = View.GONE
        emptyLayout?.visibility = View.VISIBLE
        errorLayout?.visibility = View.GONE
        contentLayout?.visibility = View.GONE
    }

    fun showEmpty(@StringRes textRes: Int) {
        emptyText?.setText(textRes)
        showEmpty()
    }

    fun showError(text: CharSequence? = null, retryText: CharSequence? = null, onRetry: (() -> Unit)? = null) {
        currentState = State.ERROR
        errorText?.text = text
        if (retryText != null && errorRetryButton is TextView) {
            errorRetryButton.text = retryText
        }
        errorRetryButton?.setOnClickListener { onRetry?.invoke() }
        loadingLayout?.visibility = View.GONE
        emptyLayout?.visibility = View.GONE
        errorLayout?.visibility = View.VISIBLE
        contentLayout?.visibility = View.GONE
    }

    fun showContent() {
        currentState = State.CONTENT
        loadingLayout?.visibility = View.GONE
        emptyLayout?.visibility = View.GONE
        errorLayout?.visibility = View.GONE
        contentLayout?.visibility = View.VISIBLE
    }

    fun updateLoadingText(text: CharSequence) {
        loadingText?.text = text
    }

    fun isLoading(): Boolean = currentState == State.LOADING
    fun isContent(): Boolean = currentState == State.CONTENT
}
