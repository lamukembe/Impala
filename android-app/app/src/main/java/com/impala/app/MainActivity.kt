package com.impala.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.impala.app.data.AppContainer
import com.impala.app.ui.ImpalaApp
import com.impala.app.viewmodel.ImpalaViewModel
import com.impala.app.viewmodel.ImpalaViewModelFactory

class MainActivity : ComponentActivity() {
    private val viewModel: ImpalaViewModel by viewModels {
        ImpalaViewModelFactory(AppContainer().repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ImpalaApp(viewModel = viewModel)
        }
    }
}
