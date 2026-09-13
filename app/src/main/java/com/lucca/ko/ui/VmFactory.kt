package com.lucca.ko.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lucca.ko.AppContainer
import com.lucca.ko.KoApp

/** Pulls the [KoApp] (and thus its [AppContainer]) out of ViewModel creation extras. */
val CreationExtras.koApp: KoApp
    get() = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as KoApp

/**
 * Boilerplate-free factory for a ViewModel built from the [AppContainer]:
 *
 * ```
 * companion object { val Factory = koFactory { PantryViewModel(it.pantryRepository) } }
 * ```
 *
 * The receiver is the [CreationExtras], so a ViewModel that needs navigation arguments can
 * still reach them via `createSavedStateHandle()`.
 */
inline fun <reified VM : ViewModel> koFactory(
    crossinline create: CreationExtras.(AppContainer) -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer { create(koApp.container) }
}
