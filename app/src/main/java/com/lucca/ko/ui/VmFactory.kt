package com.lucca.ko.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.lucca.ko.KoApp

/** Pulls the [KoApp] (and thus its [com.lucca.ko.AppContainer]) out of ViewModel creation extras. */
val CreationExtras.koApp: KoApp
    get() = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as KoApp
