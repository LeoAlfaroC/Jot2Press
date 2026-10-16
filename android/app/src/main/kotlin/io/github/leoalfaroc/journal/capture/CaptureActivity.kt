package io.github.leoalfaroc.journal.capture

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.TakePicture
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import io.github.leoalfaroc.journal.InOrder
import io.github.leoalfaroc.journal.JournalApp
import io.github.leoalfaroc.journal.Trouble
import io.github.leoalfaroc.journal.data.DraftPhoto
import io.github.leoalfaroc.journal.data.MAX_PHOTOS
import io.github.leoalfaroc.journal.data.PhotoFiles
import io.github.leoalfaroc.journal.settings.SettingsActivity
import io.github.leoalfaroc.journal.ui.JournalTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps the dialog's model across rotation. What the model does to the queue is handed to `work`, which outlives
 * the dialog and is shared by every opening of it: a `Send` or a draft being saved finishes after the dialog has
 * closed, and before the next dialog reads the draft.
 */
class CaptureViewModel(
    private val model: CaptureModel,
    private val work: InOrder,
    private val intake: PhotoIntake,
    private val gallery: Gallery,
    private val files: PhotoFiles,
) : ViewModel() {
    val ui = model.ui

    /** Kept apart from `ui`, which must answer typing at once: the notice can follow a moment later. */
    val trouble: StateFlow<Trouble?> = model.trouble.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var saving: Job? = null

    /** [fix] and [review] leave the dialog for settings and for the queue; [takePhoto] and [choosePhotos] open the camera and the picker. */
    fun actions(fix: () -> Unit, review: () -> Unit, takePhoto: () -> Unit, choosePhotos: () -> Unit) = CaptureActions(
        type = { text ->
            model.type(text)
            saving?.cancel()
            // On the main thread, like everything that cancels it: once cancelled, it hands nothing over.
            saving = viewModelScope.launch {
                delay(SAVE_AFTER_MILLIS)
                work.run { model.saveDraft() }
            }
        },
        send = {
            saving?.cancel()
            work.run { model.send() }
        },
        askDiscard = model::askDiscard,
        keep = model::keep,
        confirmDiscard = {
            saving?.cancel()
            work.run { model.confirmDiscard() }
        },
        editLast = { work.run { model.editLast() } },
        askDeleteLast = model::askDeleteLast,
        confirmDeleteLast = { work.run { model.confirmDeleteLast() } },
        refresh = { refresh() },
        fix = fix,
        review = review,
        takePhoto = takePhoto,
        choosePhotos = choosePhotos,
        removePhoto = { id -> work.run { model.removePhoto(id) } },
    )

    init {
        work.run { model.open() }
    }

    fun refresh() {
        work.run { model.refresh() }
    }

    /**
     * Copies what the picker returned, then adds it to the editor. The copying holds its place in `work` from the moment
     * of the pick, so a `Send` or a closing dialog that follows waits for the photos instead of passing them by.
     */
    fun addPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        work.run { model.addPhotos(withContext(Dispatchers.IO) { uris.mapNotNull(intake::copy) }) }
    }

    fun newCameraPhoto(): DraftPhoto = intake.newCameraPhoto()

    fun cameraTarget(photo: DraftPhoto): File = files.file(photo.file)

    /** The camera came back for the photo with this id. Its copy also goes to the gallery; if that fails, the entry keeps it. */
    fun addTaken(id: String) {
        work.run {
            val photo = withContext(Dispatchers.IO) {
                val photo = intake.taken(intake.cameraPhoto(id)) ?: return@withContext null
                // Before the photo joins the editor: a full editor deletes the copy, which must not happen under the gallery's read.
                try {
                    gallery.save(files.file(photo.file))
                } catch (e: Exception) {
                    // The gallery copy is a convenience: the entry has its own.
                }
                photo
            } ?: return@run
            model.addPhotos(listOf(photo))
        }
    }

    suspend fun thumbnailOf(photo: DraftPhoto, sizePx: Int): ImageBitmap? =
        withContext(Dispatchers.IO) { thumbnail(files.file(photo.file), sizePx) }

    /** The dialog is going away: what is in the editor is kept now, not when typing next pauses. */
    fun saveNow() {
        saving?.cancel()
        work.run { model.saveDraft() }
    }

    companion object {
        /** The draft is saved this long after the last key. */
        const val SAVE_AFTER_MILLIS = 300L

        val Factory = viewModelFactory {
            initializer {
                val application = this[APPLICATION_KEY] as JournalApp
                val container = application.container
                CaptureViewModel(
                    CaptureModel(container.queue, container.settings, container.scheduler, container.clock),
                    container.captureWork,
                    PhotoIntake(application.contentResolver, container.photoFiles),
                    container.gallery,
                    container.photoFiles,
                )
            }
        }
    }
}

/** The capture dialog: a translucent window over the home screen. Back, or a tap outside the sheet, keeps the draft. */
class CaptureActivity : ComponentActivity() {
    private val viewModel: CaptureViewModel by viewModels { CaptureViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val clock = (application as JournalApp).container.clock
        setContent {
            JournalTheme {
                val ui by viewModel.ui.collectAsStateWithLifecycle()
                val trouble by viewModel.trouble.collectAsStateWithLifecycle()
                LaunchedEffect(ui.closed) { if (ui.closed) finish() }
                val left = MAX_PHOTOS - ui.photos.size
                // The multiple picker needs room for 2: with one slot left, the single one opens.
                val pickMany = rememberLauncherForActivityResult(remember(left) { PickMultipleVisualMedia(left.coerceAtLeast(2)) }) { viewModel.addPicked(it) }
                val pickOne = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> uri?.let { viewModel.addPicked(listOf(it)) } }
                // Saved with the dialog: Android can recreate it while the camera app is open.
                var shooting by rememberSaveable { mutableStateOf<String?>(null) }
                val camera = rememberLauncherForActivityResult(TakePicture()) {
                    // The file says whether a photo was taken: some camera apps report it wrongly.
                    shooting?.let(viewModel::addTaken)
                    shooting = null
                }
                val current by rememberUpdatedState(Triple(left, pickMany, pickOne))
                val actions = remember {
                    viewModel.actions(
                        fix = { leaveFor(Intent(this, SettingsActivity::class.java)) },
                        review = { leaveFor(SettingsActivity.queueIntent(this)) },
                        takePhoto = {
                            // A second tap before the camera covers the dialog would lose the first shot's id.
                            if (shooting == null) {
                                val photo = viewModel.newCameraPhoto()
                                shooting = photo.id
                                try {
                                    camera.launch(FileProvider.getUriForFile(this, "$packageName.photos", viewModel.cameraTarget(photo)))
                                } catch (e: ActivityNotFoundException) {
                                    shooting = null
                                }
                            }
                        },
                        choosePhotos = {
                            val (room, many, one) = current
                            val request = PickVisualMediaRequest(PickVisualMedia.ImageOnly)
                            when {
                                room >= 2 -> many.launch(request)
                                room == 1 -> one.launch(request)
                            }
                        },
                    )
                }
                val density = LocalDensity.current
                val sizePx = with(density) { 72.dp.roundToPx() }
                CaptureScreen(
                    ui.copy(trouble = trouble),
                    actions,
                    now = remember { { clock.millis() } },
                    onDismiss = ::finish,
                    loadThumbnail = { viewModel.thumbnailOf(it, sizePx) },
                )
            }
        }
    }

    /** Settings opens in the app's own task, not in the dialog's. Pausing keeps the text as the draft. */
    private fun leaveFor(intent: Intent) {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onStart() {
        super.onStart()
        // The day or the last entry's grace window may have changed while the dialog was out of sight.
        viewModel.refresh()
    }

    /**
     * Android pauses this dialog before it creates the next one, and may stop it only long after:
     * saving here puts this dialog's draft in line ahead of the next dialog's reading of it.
     */
    override fun onPause() {
        super.onPause()
        viewModel.saveNow()
    }
}
