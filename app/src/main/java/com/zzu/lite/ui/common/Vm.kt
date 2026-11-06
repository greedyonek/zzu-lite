package com.zzu.lite.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** 简单工厂：为 Compose viewModel(factory = ...) 提供 lambda 创建 */
object VmFactory {
    fun <T : ViewModel> of(creator: () -> T): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = creator() as VM
        }
}
