package io.github.leoalfaroc.journal

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.leoalfaroc.journal.data.SecretBox
import kotlinx.coroutines.CoroutineScope
import java.io.File

/** A settings store in a file of its own, so that tests do not share one. Cancel `scope` when the test ends. */
fun tempStore(folder: File, scope: CoroutineScope): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = scope) { File(folder, "settings.preferences_pb") }

/** Stands in for the Android Keystore, which does not exist off a device. It is not encryption. */
class FakeBox : SecretBox {
    var broken = false

    override fun seal(plain: ByteArray): ByteArray = plain.reversedArray()

    override fun open(sealed: ByteArray): ByteArray {
        check(!broken) { "The key is gone." }
        return sealed.reversedArray()
    }
}
