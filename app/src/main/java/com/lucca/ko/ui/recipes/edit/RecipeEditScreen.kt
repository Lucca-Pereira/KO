package com.lucca.ko.ui.recipes.edit

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lucca.ko.data.images.RecipeImages
import com.lucca.ko.ui.common.EmptyState
import com.lucca.ko.ui.common.KoTopBar
import com.lucca.ko.ui.common.LoadingBox
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditScreen(
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    vm: RecipeEditViewModel = viewModel(factory = RecipeEditViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDiscard by remember { mutableStateOf(false) }

    // The camera writes straight into the file we hand it, so it only becomes the recipe photo
    // once the capture actually succeeds; a cancelled capture leaves an empty file to clean up.
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        val file = pendingPhoto
        pendingPhoto = null
        if (success && file != null) {
            RecipeImages.deleteQuietly(state.draft.imageLocalPath)
            vm.setPhoto(file.absolutePath)
        } else {
            RecipeImages.deleteQuietly(file?.absolutePath)
        }
    }

    LaunchedEffect(state.savedId) { state.savedId?.let(onSaved) }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Anything you've typed since opening the editor will be lost.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
        )
    }

    Scaffold(
        topBar = {
            KoTopBar(
                title = if (state.isNew) "New recipe" else "Edit recipe",
                navigationIcon = {
                    IconButton(onClick = { confirmDiscard = true }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.size(20.dp).padding(end = 8.dp))
                    } else {
                        TextButton(onClick = vm::save, enabled = state.canSave) { Text("Save") }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingBox(Modifier.padding(padding))

            state.error != null && state.draft.title.isBlank() && !state.isNew ->
                EmptyState(
                    title = "Recipe not found",
                    subtitle = state.error,
                    modifier = Modifier.padding(padding),
                )

            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    PhotoField(
                        localPath = state.draft.imageLocalPath,
                        remoteUrl = state.draft.imageUrl,
                        onTake = {
                            val (file, uri) = RecipeImages.newCaptureTarget(
                                context,
                                state.draft.id,
                            )
                            pendingPhoto = file
                            takePicture.launch(uri)
                        },
                        onClear = {
                            RecipeImages.deleteQuietly(state.draft.imageLocalPath)
                            vm.setPhoto(null)
                        },
                    )
                }

                item {
                    OutlinedTextField(
                        value = state.draft.title,
                        onValueChange = vm::setTitle,
                        label = { Text("Title") },
                        singleLine = true,
                        isError = state.error != null && state.draft.title.isBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NumberField(
                            value = state.draft.servingsText,
                            onValueChange = vm::setServings,
                            label = "Servings",
                            modifier = Modifier.weight(1f),
                        )
                        NumberField(
                            value = state.draft.prepText,
                            onValueChange = vm::setPrep,
                            label = "Prep (min)",
                            modifier = Modifier.weight(1f),
                        )
                        NumberField(
                            value = state.draft.cookText,
                            onValueChange = vm::setCook,
                            label = "Cook (min)",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                item {
                    OutlinedTextField(
                        value = state.draft.sourceUrl,
                        onValueChange = vm::setSourceUrl,
                        label = { Text("Link (optional)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                item {
                    TagPicker(
                        tags = state.draft.tags,
                        suggestions = state.knownTags,
                        onAdd = vm::addTag,
                        onRemove = vm::removeTag,
                    )
                }

                ingredientEditor(
                    ingredients = state.draft.ingredients,
                    onUpdate = vm::updateIngredient,
                    onRemove = vm::removeIngredient,
                    onMove = vm::moveIngredient,
                    onAdd = vm::addIngredient,
                )

                stepEditor(
                    steps = state.draft.steps,
                    onUpdate = vm::updateStep,
                    onRemove = vm::removeStep,
                    onMove = vm::moveStep,
                    onAdd = vm::addStep,
                )

                item {
                    OutlinedTextField(
                        value = state.draft.notes,
                        onValueChange = vm::setNotes,
                        label = { Text("Notes") },
                        placeholder = { Text("Anything you want to remember next time") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (state.error != null) {
                    item {
                        Text(
                            state.error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                item {
                    Button(
                        onClick = vm::save,
                        enabled = state.canSave,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    ) { Text(if (state.isNew) "Create recipe" else "Save changes") }
                }
            }
        }
    }
}

@Composable
private fun PhotoField(
    localPath: String?,
    remoteUrl: String?,
    onTake: () -> Unit,
    onClear: () -> Unit,
) {
    val file = RecipeImages.asFile(localPath)
    val model: Any? = file ?: remoteUrl?.takeIf { it.isNotBlank() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
        } else {
            Box(
                Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No photo yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTake) {
                Icon(
                    Icons.Filled.PhotoCamera,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    if (file != null) "Retake photo" else "Take photo",
                    Modifier.padding(start = 8.dp),
                )
            }
            if (file != null) {
                TextButton(onClick = onClear) { Text("Remove") }
            }
        }
    }
}

@Composable
internal fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
        modifier = modifier,
    )
}

@Composable
internal fun AddRowButton(text: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text, Modifier.padding(start = 8.dp))
    }
}
