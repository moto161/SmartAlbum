package com.example.smartalbum.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.example.smartalbum.R

/**
 * Activity切换动画工具类
 * 统一管理所有页面跳转动画，提升APP高级感
 */
object ActivityAnimationUtil {

    /**
     * 带动画启动Activity（从右侧滑入）
     */
    fun startActivityWithAnimation(context: Context, intent: Intent) {
        context.startActivity(intent)
        if (context is Activity) {
            context.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
        }
    }

    /**
     * Fragment中带动画启动Activity
     */
    fun startActivityWithAnimation(fragment: Fragment, intent: Intent) {
        fragment.startActivity(intent)
        fragment.activity?.overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
    }

    /**
     * 带动画启动Activity（淡入淡出，用于启动页等特殊场景）
     */
    fun startActivityWithFade(context: Context, intent: Intent) {
        context.startActivity(intent)
        if (context is Activity) {
            context.overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
    }

    /**
     * 带动画结束Activity（从右侧滑出）
     */
    fun finishWithAnimation(activity: Activity) {
        activity.finish()
        activity.overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }
}
