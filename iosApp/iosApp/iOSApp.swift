import SwiftUI
import BackgroundTasks
import UserNotifications
import Shared

@main
struct iOSApp: App {
    @Environment(\.scenePhase) private var scenePhase

    init() {
        AlertPoller.register()
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .onChange(of: scenePhase) { phase in
            if phase == .background { AlertPoller.schedule() }
        }
    }
}

enum Config {
    static var serverURL: String {
        Bundle.main.object(forInfoDictionaryKey: "StockCheckerServerURL") as? String ?? "http://localhost:8080"
    }
}

/// Native apps can't use Web Push. Until APNs is added, poll for new alerts with BGAppRefreshTask
/// (iOS decides when it runs) and show them as local notifications.
enum AlertPoller {
    static let taskId = "app.stockchecker.refresh"
    private static let lastSeenKey = "stockchecker.lastSeenAlertId"

    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            schedule()
            let api = IosEntryKt.createApi(baseUrl: Config.serverURL)
            guard api.hasSession() else { task.setTaskCompleted(success: true); return }
            api.alerts { alerts, error in
                if let alerts = alerts { notifyNew(alerts) }
                task.setTaskCompleted(success: error == nil)
            }
        }
    }

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: taskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }

    /// Shows local notifications for alerts newer than the last one seen (first run records a baseline).
    static func notifyNew(_ alerts: [Shared.Alert]) {
        let defaults = UserDefaults.standard
        let hasBaseline = defaults.object(forKey: lastSeenKey) != nil
        let lastSeen = Int64(defaults.integer(forKey: lastSeenKey))
        // An empty list still sets a baseline of 0, so the very first alert after sign-in is shown.
        let newest = alerts.map({ $0.id }).max() ?? 0
        if hasBaseline {
            for alert in AlertTracker.shared.newSince(alerts: alerts, lastSeenId: lastSeen) {
                let content = UNMutableNotificationContent()
                content.title = alert.title
                content.body = alert.body
                content.sound = .default
                let request = UNNotificationRequest(identifier: "alert-\(alert.id)", content: content, trigger: nil)
                UNUserNotificationCenter.current().add(request)
            }
        }
        if !hasBaseline || newest > lastSeen { defaults.set(Int(newest), forKey: lastSeenKey) }
    }
}
