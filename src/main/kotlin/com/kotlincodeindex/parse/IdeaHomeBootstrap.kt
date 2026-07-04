package com.kotlincodeindex.parse

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

object IdeaHomeBootstrap {
    private const val RESOURCE_PREFIX = "idea-home/"
    private const val MARKER = ".kotlin-index-idea-home"
    private val SUBDIRS = listOf("config", "system", "plugins")

    fun ensure() {
        if (System.getProperty("idea.home.path") != null) {
            return
        }
        val home = extractBundledHome()
        System.setProperty("idea.home.path", home.toString())
        System.setProperty("idea.config.path", home.resolve("config").toString())
        System.setProperty("idea.system.path", home.resolve("system").toString())
        System.setProperty("idea.plugins.path", home.resolve("plugins").toString())
    }

    private fun extractBundledHome(): Path {
        val cacheRoot = Path.of(System.getProperty("user.home"), ".kotlin-index", "idea-home")
        cacheRoot.createDirectories()
        val marker = cacheRoot.resolve(MARKER)
        if (marker.exists() && cacheRoot.isDirectory() && SUBDIRS.all { cacheRoot.resolve(it).isDirectory() }) {
            return cacheRoot
        }

        val classLoader = IdeaHomeBootstrap::class.java.classLoader
        SUBDIRS.forEach { subdir ->
            val destDir = cacheRoot.resolve(subdir)
            destDir.createDirectories()
            val resourcePath = "${RESOURCE_PREFIX}$subdir/.keep"
            val stream = classLoader.getResourceAsStream(resourcePath)
                ?: error("Bundled $resourcePath not found on classpath — rebuild shadow JAR")
            stream.use { input ->
                Files.copy(input, destDir.resolve(".keep"), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        Files.writeString(marker, "extracted")
        return cacheRoot
    }
}
