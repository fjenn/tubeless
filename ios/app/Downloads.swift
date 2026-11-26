import BackgroundTasks
import UIKit
import TubelessKit

// The app side of background downloads (the downloads themselves are Kotlin: common/ios OfflineIos.kt,
// with ios/src Downloads.kt between them and this file).

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        DownloadActivity.register()
        return true
    }

    /// iOS started (or woke) the app because a background download session has finished tasks or
    /// needs credentials: the Kotlin side makes its sessions again under the same identifiers, takes
    /// the files, then calls `completionHandler` so iOS can suspend the app again.
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        DownloadProgress.shared.backgroundSessionEvents(identifier: identifier) {
            DispatchQueue.main.async { completionHandler() }
        }
    }
}

/// iOS 26+: while downloads run, a continued-processing task shows their progress in the system's
/// banner and on the lock screen, and keeps the app running when it is left. Started when downloads
/// start in the app (it must come from something the user did while the app was in front).
enum DownloadActivity {
    /// BGTaskSchedulerPermittedIdentifiers in Info.plist lists "dev.tubeless.app.downloads.*"
    static let prefix = "dev.tubeless.app.downloads"
    private static var registered = false

    static func register() {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            // the exact identifier that start() submits: registered as "….*" iOS 27 found no handler for
            // "….progress" and ended the app (NSInternalInconsistencyException) as the first download began
            registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: prefix + ".progress", using: nil) { task in
                guard let task = task as? BGContinuedProcessingTask else { task.setTaskCompleted(success: false); return }
                run(task)
            }
            DownloadProgress.shared.onStarted = { start() }
        }
        #endif
    }

    #if compiler(>=6.2)
    @available(iOS 26.0, *)
    private static func start() {
        guard registered else { return }   // never submit what has no handler: iOS ends the app for that
        let request = BGContinuedProcessingTaskRequest(
            identifier: prefix + ".progress", title: "Downloading", subtitle: DownloadProgress.shared.title())
        request.strategy = .queue
        do { try BGTaskScheduler.shared.submit(request) } catch { print("DownloadActivity: not started: \(error)") }
    }

    @available(iOS 26.0, *)
    private static func run(_ task: BGContinuedProcessingTask) {
        var stopped = false
        task.expirationHandler = { stopped = true }
        task.progress.totalUnitCount = 1000
        func tick() {
            if stopped { task.setTaskCompleted(success: false); return }
            let n = DownloadProgress.shared.count()
            if n == 0 {
                task.progress.completedUnitCount = 1000
                task.setTaskCompleted(success: true)
                return
            }
            task.progress.completedUnitCount = Int64(DownloadProgress.shared.progress() * 1000)
            task.updateTitle("Downloading", subtitle: DownloadProgress.shared.title())
            DispatchQueue.main.asyncAfter(deadline: .now() + 1, execute: tick)
        }
        DispatchQueue.main.async(execute: tick)
    }
    #endif
}
