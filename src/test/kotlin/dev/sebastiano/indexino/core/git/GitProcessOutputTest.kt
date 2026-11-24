package dev.sebastiano.indexino.core.git

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitProcessOutputTest {
    @Test
    fun `readStdout keeps Git warnings out of parsed output`() {
        val repo = committedAutocrlfRepository()

        // Fixture validity: diffing against a freshly copied index makes Git re-verify content
        // and print "LF will be replaced by CRLF" warnings on stderr with a zero exit code.
        // A merged-stream reader (the pattern this helper replaces) would parse that text.
        val merged = runMergedAgainstFreshIndexCopy(repo, "diff", "HEAD", "--", ".")
        assertTrue(
            "warning:" in merged,
            "Expected the autocrlf fixture to trigger Git warnings, got: $merged",
        )

        // The helper returns stdout only, so parsed values stay exact.
        assertEquals("A.txt\nB.txt\n", GitProcessOutput.readStdout(repo, listOf("ls-files")))
        val head = GitProcessOutput.readStdout(repo, listOf("rev-parse", "HEAD"))?.trim()
        checkNotNull(head)
        assertTrue(head.length == 40, "rev-parse HEAD returned: $head")
        assertTrue("warning:" !in head, "A warning reached the parsed rev-parse output")

        // A failing command yields null instead of Git's error text.
        assertNull(GitProcessOutput.readStdout(repo, listOf("remote", "get-url", "origin")))
    }

    private fun committedAutocrlfRepository(): Path {
        val repo = createTempDirectory("git-stdout-").resolve("repo")
        Files.createDirectories(repo)
        runGit(repo, "init", "-q", ".")
        runGit(repo, "config", "core.autocrlf", "true")
        Files.writeString(repo.resolve("A.txt"), "line1\nline2\n")
        Files.writeString(repo.resolve("B.txt"), "crlf1\r\ncrlf2\r\n")
        runGit(repo, "add", "A.txt")
        runGit(repo, "add", "B.txt")
        runGit(
            repo,
            "-c",
            "commit.gpgsign=false",
            "-c",
            "user.email=test@indexino.invalid",
            "-c",
            "user.name=Indexino Test",
            "commit",
            "-q",
            "-m",
            "base",
        )
        // Age every file, .git included, so the next diff re-verifies content instead of
        // trusting recorded stat data.
        val past = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000)
        Files.walk(repo).use { paths ->
            paths.filter(Files::isRegularFile).forEach { Files.setLastModifiedTime(it, past) }
        }
        return repo.toRealPath()
    }

    /** The pre-helper pattern: stderr merged into stdout, with a private index copy like diff. */
    private fun runMergedAgainstFreshIndexCopy(repo: Path, vararg arguments: String): String {
        val directory = Files.createTempDirectory("git-stdout-index-")
        try {
            val copy = directory.resolve("index")
            Files.copy(repo.resolve(".git/index"), copy, StandardCopyOption.COPY_ATTRIBUTES)
            val process =
                ProcessBuilder(listOf("git", "-C", repo.toString()) + arguments.toList())
                    .redirectErrorStream(true)
                    .apply {
                        environment()["GIT_INDEX_FILE"] = copy.toString()
                        environment()["GIT_OPTIONAL_LOCKS"] = "0"
                    }
                    .start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
            return output
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun runGit(repo: Path, vararg arguments: String) {
        val process =
            ProcessBuilder(listOf("git", "-C", repo.toString()) + arguments.toList())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
    }
}
