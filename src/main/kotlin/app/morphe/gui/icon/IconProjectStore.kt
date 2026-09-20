/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-desktop
 */

package app.morphe.gui.icon

import app.morphe.engine.util.AtomicFiles
import app.morphe.gui.util.Logger
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists an [IconProject] to `morphe-data/icons/<packageName>/project.json` so a
 * custom icon can be reopened and edited later instead of rebuilt from scratch.
 * The generated mipmap folder is derived output; this is the editable source.
 */
object IconProjectStore {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        // Use a discriminator that can't collide with a data-class property (e.g.
        // Gradient.type) — a "#"-prefixed key is not a valid Kotlin identifier.
        classDiscriminator = "#kind"
    }

    private fun file(packageName: String) = File(IconExporter.projectDir(packageName), "project.json")

    fun save(project: IconProject, packageName: String) {
        val target = file(packageName)
        // AtomicFiles.write creates the parent directory itself.
        runCatching { AtomicFiles.write(target, json.encodeToString(IconProject.serializer(), project)) }
            .onFailure { Logger.error("IconProjectStore.save failed for $packageName", it) }
    }

    fun load(packageName: String): IconProject? {
        val f = file(packageName)
        if (!f.exists()) return null
        return runCatching { json.decodeFromString(IconProject.serializer(), f.readText()) }
            .onFailure { Logger.error("IconProjectStore.load failed for $packageName", it) }
            .getOrNull()
    }
}
