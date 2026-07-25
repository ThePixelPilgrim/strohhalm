package de.nereide.strohhalm

import android.app.Application

/**
 * Application entry point. Builds the [AppContainer] which workers and screens
 * reach via `(context.applicationContext as StrohhalmApp).container`.
 */
class StrohhalmApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
