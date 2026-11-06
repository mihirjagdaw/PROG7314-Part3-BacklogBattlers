package com.backlogbattlers.app.profile

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import okio.Path.Companion.toOkioPath
import okio.FileSystem
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import com.backlogbattlers.app.data.repository.ProfileCustomizationRepositoryImpl
import com.backlogbattlers.app.domain.model.ProfileCustomization
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// the real DataStore, written to a temporary file, so the per account keys are tested as stored
class ProfileCustomizationRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // okio storage replaces the file with an atomic move, which windows allows. the default
    // file storage (datastore 1.1.7, the version firebase needs) can't rename over the old file there
    private val store = PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { folder.root.toOkioPath() / "profile_customization.preferences_pb" },
        scope = scope,
    )

    // whoever is "signed in" for the repository
    private var signedIn: String? = "alice"
    private val repository = ProfileCustomizationRepositoryImpl(store, currentUserId = { signedIn })

    private val custom = ProfileCustomization(pictureId = "knight")

    @After
    fun tearDown() {
        scope.cancel()
    }

    private suspend fun current() = repository.observeCustomization().first()

    @Test
    fun `nothing saved yet gives the defaults, with no picture`() = runBlocking {
        val defaults = current()

        assertEquals(ProfileCustomization(), defaults)
        assertEquals(null, defaults.pictureId)
    }

    @Test
    fun `a saved setup is read back exactly`() = runBlocking {
        repository.save(custom)

        assertEquals(custom, current())
    }

    @Test
    fun `each account keeps its own setup`() = runBlocking {
        repository.save(custom)
        signedIn = "bob"

        assertEquals(ProfileCustomization(), current())
        repository.save(ProfileCustomization(pictureId = "wizard"))
        signedIn = "alice"
        assertEquals(custom, current())
    }

    @Test
    fun `removing the picture forgets it`() = runBlocking {
        repository.save(custom)

        repository.save(custom.copy(pictureId = null))

        assertEquals(null, current().pictureId)
    }

    @Test
    fun `nobody signed in sees the defaults and saves nothing`() = runBlocking {
        signedIn = null
        repository.save(custom)

        assertEquals(ProfileCustomization(), current())
        signedIn = "alice"
        assertEquals(ProfileCustomization(), current())
    }

    @Test
    fun `deleting an account clears its setup and leaves the others`() = runBlocking {
        repository.save(custom)
        signedIn = "bob"
        repository.save(ProfileCustomization(pictureId = "wizard"))

        repository.clear("alice")

        assertEquals("wizard", current().pictureId)
        signedIn = "alice"
        assertEquals(ProfileCustomization(), current())
    }
}
