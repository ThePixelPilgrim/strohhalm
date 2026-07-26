package de.nereide.strohhalm

import android.app.Application
import de.nereide.strohhalm.domain.AndroidSystemReader
import de.nereide.strohhalm.domain.SshdEnvironment

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
    }
}
