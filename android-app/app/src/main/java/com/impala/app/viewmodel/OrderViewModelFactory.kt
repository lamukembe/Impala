package com.impala.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.impala.app.data.DemoConnectivityMonitor
import com.impala.core.OrderRepository
import com.impala.core.UserSession

class ImpalaViewModelFactory(
    private val repository: OrderRepository,
    private val session: UserSession,
    private val connectivity: DemoConnectivityMonitor
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ImpalaViewModel::class.java)) {
            return ImpalaViewModel(repository, session, connectivity) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
