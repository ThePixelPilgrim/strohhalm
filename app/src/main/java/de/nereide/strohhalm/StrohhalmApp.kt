package de.nereide.strohhalm

import android.app.Application
import de.nereide.strohhalm.domain.AndroidSystemReader
import de.nereide.strohhalm.domain.SshdEnvironment
import kotlinx.coroutines.launch

/**
 * Application entry point. Builds the [AppContainer] which workers and screens
 * reach via `(context.applicationContext as StrohhalmApp).container`.
 */
class StrohhalmApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        // Before anything else. MINA SSHD resolves `~` in static initialisers,
        // and Android has no user.home — if those holders run first they fail
        // permanently for the life of the process, and every later SSH attempt
        // throws NoClassDefFoundError with an unrelated-looking symptom.
        SshdEnvironment.install(filesDir)
        AndroidSystemReader.install()

        container = DefaultAppContainer(this)

        // Prunes superseded archives whenever a sync finishes. Launched, never
        // awaited, so a slow delete cannot delay the sync's completion write.
        container.archiveMaintenance.observe(container.syncRunner.running)

        // A process killed mid-sync leaves rows claiming to be running forever,
        // implying work is happening when nothing is.
        container.applicationScope.launch {
            runCatching { container.syncRunner.resetStale() }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Only the severe level. The lesser ones fire whenever the app
        // backgrounds, and dropping the cache on every backgrounding would
        // defeat reuse entirely.
        if (level >= TRIM_MEMORY_COMPLETE) {
            container.applicationScope.launch { container.archiveMaintenance.dropEverything() }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        container.applicationScope.launch { container.archiveMaintenance.dropEverything() }
    }
}
