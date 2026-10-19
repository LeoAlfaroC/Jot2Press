package io.github.leoalfaroc.jot2press

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.github.leoalfaroc.jot2press.data.SecretBox
import kotlinx.coroutines.CoroutineScope
import java.io.File

/** A settings store in a file of its own, so that tests do not share one. Cancel `scope` when the test ends. */
fun tempStore(folder: File, scope: CoroutineScope): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(scope = scope) { File(folder, "settings.preferences_pb") }

/** Stands in for the Android Keystore, which does not exist off a device. It is not encryption. */
class FakeBox : SecretBox {
    var broken = false
    /** When set, sealing throws, as a Keystore can after some system updates. */
    var sealBroken = false

    override fun seal(plain: ByteArray): ByteArray {
        check(!sealBroken) { "The Keystore failed." }
        return plain.reversedArray()
    }

    override fun open(sealed: ByteArray): ByteArray {
        check(!broken) { "The key is gone." }
        return sealed.reversedArray()
    }
}
