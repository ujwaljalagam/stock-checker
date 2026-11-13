package app.stockchecker.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.stockchecker.model.Alert
import app.stockchecker.model.StockStatus
import app.stockchecker.model.Watch
import app.stockchecker.presentation.AppState
import app.stockchecker.presentation.AppStore
import app.stockchecker.presentation.Session
import java.text.DateFormat
import java.util.Date

@Composable
fun StockCheckerTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF0F766E)), content = content)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(store: AppStore, onAlerts: (List<Alert>) -> Unit) {
    val state by store.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.signedIn, state.alerts) { if (state.signedIn) onAlerts(state.alerts) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stock Checker") },
                actions = { if (state.signedIn) TextButton(onClick = store::signOut) { Text("Sign out") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Messages(state, store)
            when (val s = state.session) {
                Session.Unknown -> Unit
                Session.SignedOut -> EmailStep(store)
                is Session.CodeSent -> CodeStep(store, s)
                is Session.SignedIn -> Dashboard(store, state)
            }
        }
    }
}

@Composable
private fun Messages(state: AppState, store: AppStore) {
    val text = state.error ?: state.info ?: return
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), color = if (state.error != null) MaterialTheme.colorScheme.error else Color(0xFF15803D))
            TextButton(onClick = store::dismissMessages) { Text("OK") }
        }
    }
}

@Composable
private fun EmailStep(store: AppStore) {
    var email by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Get an alert when it's on the shelf", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        Button(onClick = { store.requestCode(email) }) { Text("Email me a sign-in code") }
    }
}

@Composable
private fun CodeStep(store: AppStore, session: Session.CodeSent) {
    var code by rememberSaveable { mutableStateOf("") }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Enter the 6-digit code sent to ${session.email}")
        session.devCode?.let { Text("Dev mode code: $it", color = Color.Gray) }
        OutlinedTextField(
            code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        Row {
            Button(onClick = { store.verifyCode(code) }) { Text("Sign in") }
            TextButton(onClick = store::changeEmail) { Text("Use a different email") }
        }
    }
}

@Composable
private fun Dashboard(store: AppStore, state: AppState) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    PrimaryTabRow(selectedTabIndex = tab) {
        Tab(tab == 0, { tab = 0 }, text = { Text("Watches (${state.watches.size})") })
        Tab(tab == 1, { tab = 1 }, text = { Text("Alerts (${state.alerts.size})") })
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tab == 0) {
            item { AddWatchCard(store, state) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Your watches", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = store::refresh) { Text("Refresh") }
                }
            }
            if (state.watches.isEmpty()) item { Text("No watches yet.", color = Color.Gray) }
            items(state.watches, key = { it.id }) { WatchRow(it, store) }
        } else {
            item {
                Text(
                    "Alerts also arrive by email. This app checks for new alerts in the background about every 15 minutes.",
                    color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (state.alerts.isEmpty()) item { Text("No alerts yet.", color = Color.Gray) }
            items(state.alerts, key = { it.id }) { AlertRow(it) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddWatchCard(store: AppStore, state: AppState) {
    var retailerId by rememberSaveable { mutableStateOf<String?>(null) }
    var sku by rememberSaveable { mutableStateOf("") }
    var zip by rememberSaveable { mutableStateOf("") }
    var radius by rememberSaveable { mutableStateOf("25") }
    var name by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val retailer = state.retailers.firstOrNull { it.id == retailerId } ?: state.retailers.firstOrNull()

    Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add a watch", style = MaterialTheme.typography.titleMedium)
            ExposedDropdownMenuBox(expanded, { expanded = it }) {
                OutlinedTextField(
                    retailer?.name ?: "", {}, readOnly = true, label = { Text("Retailer") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded, { expanded = false }) {
                    state.retailers.forEach { r ->
                        DropdownMenuItem(text = { Text(r.name) }, onClick = { retailerId = r.id; expanded = false })
                    }
                }
            }
            OutlinedTextField(sku, { sku = it }, label = { Text("SKU") }, supportingText = { Text(retailer?.skuHint ?: "") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row {
                OutlinedTextField(
                    zip, { zip = it.filter(Char::isDigit).take(5) }, label = { Text("ZIP") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    radius, { radius = it.filter(Char::isDigit).take(3) }, label = { Text("Miles") }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(
                enabled = retailer != null,
                onClick = {
                    store.addWatch(retailer!!.id, sku, zip, radius.toIntOrNull() ?: 25, name)
                    sku = ""; name = ""
                },
            ) { Text("Watch") }
        }
    }
}

@Composable
private fun WatchRow(watch: Watch, store: AppStore) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(watch.product.name ?: watch.product.sku, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                AssistChip(onClick = {}, label = { Text(statusLabel(watch.lastStatus)) })
            }
            Text("SKU ${watch.product.sku} · ${watch.zip} (${watch.radiusMiles} mi)${watch.lastStoreName?.takeIf { watch.lastStatus.isAvailable }?.let { " · $it" } ?: ""}", color = Color.Gray)
            Text("Last checked ${formatTime(watch.lastCheckedAt)}${if (watch.active) "" else " · paused"}", color = Color.Gray)
            watch.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row {
                TextButton(onClick = { store.checkNow(watch.id) }) { Text("Check now") }
                TextButton(onClick = { store.toggleWatch(watch.id) }) { Text(if (watch.active) "Pause" else "Resume") }
                TextButton(onClick = { store.removeWatch(watch.id) }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun AlertRow(alert: Alert) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(alert.title, fontWeight = FontWeight.SemiBold)
            Text(alert.body)
            val delivery = listOfNotNull(
                alert.pushSentAt?.let { if (alert.shownAt != null) "push shown" else "push sent" },
                alert.emailedAt?.let { "emailed" },
            ).joinToString(" · ")
            Text("${formatTime(alert.createdAt)}${if (delivery.isNotEmpty()) " · $delivery" else ""}", color = Color.Gray)
        }
    }
}

private fun statusLabel(status: StockStatus) = when (status) {
    StockStatus.IN_STOCK -> "In stock"
    StockStatus.LIMITED -> "Low stock"
    StockStatus.OUT_OF_STOCK -> "Out of stock"
    StockStatus.UNKNOWN -> "Not checked"
}

private fun formatTime(ms: Long?) = ms?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "never"
