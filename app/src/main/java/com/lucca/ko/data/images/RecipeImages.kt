package com.lucca.ko.data.images

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Photos the user takes for a recipe, stored under `filesDir/recipe_images/`.
 *
 * The path is what goes in `Recipe.imageLocalPath`; Coil loads a `File` directly, so nothing
 * needs a content URI except the camera, which gets one through [FileProvider].
 */
object RecipeImages {

    private const val DIR = "recipe_images"

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** An empty file for the camera to write into, plus the URI to hand the camera intent. */
    fun newCaptureTarget(context: Context, recipeId: Long): Pair<File, Uri> {
        val file = File(dir(context), "recipe_${recipeId}_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return file to uri
    }

    fun asFile(path: String?): File? = path?.takeIf { it.isNotBlank() }?.let(::File)

    /**
     * Deletes a photo that is no longer referenced. Best-effort: a leftover file wastes a little
     * space, while failing a save because a delete failed would be worse.
     */
    fun deleteQuietly(path: String?) {
        runCatching { asFile(path)?.delete() }
    }
}
