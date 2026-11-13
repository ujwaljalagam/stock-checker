import SwiftUI
import Shared

/// Bridges the shared Kotlin AppStore (StateFlow) into SwiftUI.
@MainActor
final class StoreModel: ObservableObject {
    let store: AppStore
    @Published private(set) var state: AppState
    private var subscription: Shared.Cancellable?

    init() {
        store = IosEntryKt.createAppStore(baseUrl: Config.serverURL)
        state = store.current
        subscription = store.observe { [weak self] newState in
            self?.state = newState
            if newState.signedIn { AlertPoller.notifyNew(newState.alerts) }
        }
        store.start()
    }

    deinit { subscription?.cancel() }
}

struct ContentView: View {
    @StateObject private var model = StoreModel()

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if model.state.loading { ProgressView().padding(4) }
                if let message = model.state.error ?? model.state.info {
                    HStack {
                        Text(message).foregroundColor(model.state.error != nil ? .red : .green)
                        Spacer()
                        Button("OK") { model.store.dismissMessages() }
                    }
                    .padding()
                }
                content
            }
            .navigationTitle("Stock Checker")
            .toolbar {
                if model.state.signedIn {
                    Button("Sign out") { model.store.signOut() }
                }
            }
        }
    }

    @ViewBuilder private var content: some View {
        let session = model.state.session
        if let codeSent = session as? SessionCodeSent {
            CodeView(store: model.store, session: codeSent)
        } else if session is SessionSignedIn {
            DashboardView(store: model.store, state: model.state)
        } else if session is SessionSignedOut {
            EmailView(store: model.store)
        } else {
            Spacer()
        }
    }
}

struct EmailView: View {
    let store: AppStore
    @State private var email = ""

    var body: some View {
        Form {
            Section(header: Text("Get an alert when it's on the shelf")) {
                TextField("Email", text: $email)
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Email me a sign-in code") { store.requestCode(email: email) }
            }
        }
    }
}

struct CodeView: View {
    let store: AppStore
    let session: SessionCodeSent
    @State private var code = ""

    var body: some View {
        Form {
            Section(header: Text("Enter the 6-digit code sent to \(session.email)")) {
                if let dev = session.devCode { Text("Dev mode code: \(dev)").foregroundColor(.secondary) }
                TextField("Code", text: $code).keyboardType(.numberPad).textContentType(.oneTimeCode)
                Button("Sign in") { store.verifyCode(code: code) }
                Button("Use a different email") { store.changeEmail() }
            }
        }
    }
}

struct DashboardView: View {
    let store: AppStore
    let state: AppState
    @State private var retailerId = ""
    @State private var sku = ""
    @State private var zip = ""
    @State private var radius = "25"
    @State private var name = ""

    var body: some View {
        List {
            Section(header: Text("Add a watch")) {
                Picker("Retailer", selection: $retailerId) {
                    ForEach(state.retailers, id: \.id) { r in Text(r.name).tag(r.id) }
                }
                TextField("SKU", text: $sku).textInputAutocapitalization(.characters)
                if let hint = state.retailers.first(where: { $0.id == selectedRetailer })?.skuHint {
                    Text(hint).font(.footnote).foregroundColor(.secondary)
                }
                TextField("ZIP", text: $zip).keyboardType(.numberPad)
                TextField("Radius (miles)", text: $radius).keyboardType(.numberPad)
                TextField("Name (optional)", text: $name)
                Button("Watch") {
                    store.addWatch(retailer: selectedRetailer, sku: sku, zip: zip, radiusMiles: Int32(radius) ?? 25, productName: name)
                    sku = ""; name = ""
                }
                .disabled(selectedRetailer.isEmpty)
            }

            Section(header: HStack { Text("Your watches"); Spacer(); Button("Refresh") { store.refresh() } }) {
                if state.watches.isEmpty { Text("No watches yet.").foregroundColor(.secondary) }
                ForEach(state.watches, id: \.id) { watch in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(watch.product.name ?? watch.product.sku).bold()
                            Spacer()
                            Text(label(watch.lastStatus)).font(.caption).padding(4)
                                .background(watch.lastStatus.isAvailable ? Color.green.opacity(0.2) : Color.gray.opacity(0.2))
                                .cornerRadius(6)
                        }
                        Text("SKU \(watch.product.sku) · \(watch.zip) (\(watch.radiusMiles) mi)")
                            .font(.footnote).foregroundColor(.secondary)
                        if let error = watch.lastError { Text(error).font(.footnote).foregroundColor(.red) }
                        HStack {
                            Button("Check now") { store.checkNow(id: watch.id) }
                            Button(watch.active ? "Pause" : "Resume") { store.toggleWatch(id: watch.id) }
                            Button("Remove", role: .destructive) { store.removeWatch(id: watch.id) }
                        }
                        .buttonStyle(.borderless)
                        .font(.footnote)
                    }
                }
            }

            Section(header: Text("Alert history"), footer: Text("Alerts also arrive by email. The app checks for new alerts in the background when iOS allows it.")) {
                if state.alerts.isEmpty { Text("No alerts yet.").foregroundColor(.secondary) }
                ForEach(state.alerts, id: \.id) { alert in
                    VStack(alignment: .leading) {
                        Text(alert.title).bold()
                        Text(alert.body).font(.footnote)
                    }
                }
            }
        }
        .refreshable { store.refresh() }
    }

    private var selectedRetailer: String {
        retailerId.isEmpty ? (state.retailers.first?.id ?? "") : retailerId
    }

    private func label(_ status: StockStatus) -> String {
        switch status {
        case .inStock: return "In stock"
        case .limited: return "Low stock"
        case .outOfStock: return "Out of stock"
        default: return "Not checked"
        }
    }
}
