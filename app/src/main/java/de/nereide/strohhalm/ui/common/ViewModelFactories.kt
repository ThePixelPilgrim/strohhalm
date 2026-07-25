package de.nereide.strohhalm.ui.common

import android.app.Application
import androidx.lifecycle.viewmodel.CreationExtras
import de.nereide.strohhalm.AppContainer
import de.nereide.strohhalm.StrohhalmApp

/** Resolves the [Application] from any [CreationExtras] used by a ViewModel factory. */
fun CreationExtras.application(): Application =
    this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application

/** Resolves the app's [AppContainer] from any [CreationExtras] used by a ViewModel factory. */
fun CreationExtras.appContainer(): AppContainer =
    (application() as StrohhalmApp).container
