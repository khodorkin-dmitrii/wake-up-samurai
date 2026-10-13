package com.yavin.wakeupsamurai.data.audio

import android.content.Context
import com.yavin.wakeupsamurai.tts.AudioFileValidator
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioAssetStorage @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val validator: AudioFileValidator,
) : AudioAssetFileStore {
    override fun relativePath(file: File): String = file.relativeTo(context.filesDir).invariantSeparatorsPath

    override fun resolve(relativePath: String): File? {
        val root = context.filesDir.canonicalFile
        val resolved = File(root, relativePath).canonicalFile
        return resolved.takeIf { it.path.startsWith(root.path + File.separator) }
    }

    override fun isReadable(relativePath: String): Boolean = resolve(relativePath)?.let {
        it.isFile && it.canRead() && it.length() > 0L
    } == true

    override fun completedFiles(): List<File> = File(context.filesDir, "speech")
        .listFiles { file -> file.isFile && file.extension.equals("mp3", ignoreCase = true) }
        ?.toList()
        .orEmpty()

    override suspend fun validatedDuration(file: File): Long? = runCatching { validator.validate(file) }.getOrNull()
}

interface AudioAssetFileStore {
    fun relativePath(file: File): String
    fun resolve(relativePath: String): File?
    fun isReadable(relativePath: String): Boolean
    fun completedFiles(): List<File>
    suspend fun validatedDuration(file: File): Long?
}
