package io.rebble.libpebblecommon.util

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.Path as NioPath

/**
 * XDG base-directory resolution for the Linux desktop build.
 *
 * Mirrors the role of Android's `cacheDir` / `filesDir`: transient artifacts go under
 * `$XDG_CACHE_HOME` (default `~/.cache/libpebble3`), persisted data under `$XDG_DATA_HOME`
 * (default `~/.local/share/libpebble3`).
 */
object JvmPaths {
    private const val APP_DIR = "libpebble3"

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private val home: String get() = System.getProperty("user.home") ?: "."

    private fun ensure(path: NioPath): NioPath {
        Files.createDirectories(path)
        return path
    }

    val cacheHome: NioPath
        get() = ensure(Paths.get(env("XDG_CACHE_HOME") ?: "$home/.cache", APP_DIR))

    val dataHome: NioPath
        get() = ensure(Paths.get(env("XDG_DATA_HOME") ?: "$home/.local/share", APP_DIR))

    fun cacheSubdir(subdir: String): NioPath = ensure(cacheHome.resolve(subdir))

    fun dataSubdir(subdir: String): NioPath = ensure(dataHome.resolve(subdir))
}
