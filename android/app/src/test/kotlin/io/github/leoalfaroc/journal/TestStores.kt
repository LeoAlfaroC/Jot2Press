package io.github.leoalfaroc.journal

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import java.io.File

/** A settings store in a file of its own, so that tests do not share one. Cancel `scope` when the test ends. */
fun tempStore(folder: File, scope: CoroutineScope): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = scope) { File(folder, "settings.preferences_pb") }
