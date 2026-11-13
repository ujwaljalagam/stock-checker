package app.stockchecker.android

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.stockchecker.presentation.AppStore

/** Hosts the shared [AppStore] so it survives configuration changes. */
class AppViewModel(app: StockCheckerApp) : ViewModel() {
    val store = AppStore(app.api, app.api, app.api, viewModelScope).also { it.start() }
}

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        val app = application as StockCheckerApp
        setContent {
            val vm: AppViewModel = viewModel(factory = viewModelFactory { initializer { AppViewModel(app) } })
            StockCheckerTheme { AppScreen(vm.store) { alerts -> AlertPollWorker.notifyNew(this, alerts) } }
        }
    }
}
