// SPDX-License-Identifier: UEL-1.0
package dev.sebastiano.indexino.acceptance

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir

internal class WorktreeAcceptanceDriverTest {
    @TempDir lateinit var temporary: Path

    @Test fun `nested repository survives every worktree and branch transition`() = matrix(true)

    @Test
    fun `driver measures worktree and checkout readiness and restores fixture`() = matrix(false)

    private fun matrix(nested: Boolean) {
        val root = Files.createDirectory(temporary.resolve("owned"))
        Files.writeString(root.resolve(".indexino-benchmark-owned"), "fixture\n")
        val workspace = Files.createDirectory(root.resolve("workspace"))
        Files.writeString(
            workspace.resolve("settings.gradle.kts"),
            "rootProject.name = \"fixture\"\n" + if (nested) "include(\":android\")\n" else "",
        )
        val source = workspace.resolve("src/main/java/Seed.java")
        Files.createDirectories(source.parent)
        val original = "package fixture; class Seed {}\n"
        Files.writeString(source, original)
        git(workspace, "init")
        git(workspace, "add", ".")
        git(
            workspace,
            "-c",
            "user.name=Fixture",
            "-c",
            "user.email=f@invalid",
            "commit",
            "-m",
            "seed",
        )
        if (nested) {
            val child = Files.createDirectory(workspace.resolve("android"))
            Files.createDirectories(child.resolve("src/main/java"))
            Files.writeString(
                child.resolve("src/main/java/Nested.java"),
                "package nested; class Nested {}",
            )
            git(child, "init")
            git(child, "add", ".")
            git(
                child,
                "-c",
                "user.name=Fixture",
                "-c",
                "user.email=f@invalid",
                "commit",
                "-m",
                "nested",
            )
            Files.writeString(workspace.resolve(".gitignore"), "android/\n")
            git(workspace, "add", ".gitignore")
            git(
                workspace,
                "-c",
                "user.name=Fixture",
                "-c",
                "user.email=f@invalid",
                "commit",
                "-m",
                "ignore nested",
            )
        }
        val plan = temporary.resolve("plan.json")
        Files.writeString(plan, plan(nested).toString())
        val reportPath = temporary.resolve("report.json")

        val execution = runCatching {
            WorktreeAcceptanceDriver.main(
                arrayOf(root.toString(), plan.toString(), reportPath.toString())
            )
        }
        assertTrue(execution.isSuccess, execution.exceptionOrNull()?.stackTraceToString())

        val report = Json.parseToJsonElement(Files.readString(reportPath)).jsonObject
        assertEquals("passed", report.getValue("status").jsonPrimitive.content)
        assertEquals(
            listOf(
                "newCheckoutSeed",
                "sameCommit",
                "freshCacheControl",
                "divergedWorktree",
                "branchSwitch",
                "switchBack",
            ),
            report.getValue("samples").jsonArray.map {
                it.jsonObject.getValue("scenario").jsonPrimitive.content
            },
        )
        assertTrue(
            report.getValue("samples").jsonArray.all {
                it.jsonObject.getValue("phaseEvents").jsonArray.isNotEmpty()
            }
        )
        assertEquals(original, Files.readString(source))
        assertTrue(git(workspace, "status", "--porcelain").isBlank())
        if (nested) {
            assertEquals(
                "package nested; class Nested {}",
                Files.readString(workspace.resolve("android/src/main/java/Nested.java")),
            )
            val samples = report.getValue("samples").jsonArray
            assertTrue(
                samples.all { it.jsonObject["repositoryCount"]?.jsonPrimitive?.content == "2" }
            )
        }
    }

    private fun plan(nested: Boolean) = buildJsonObject {
        put("schema", 1)
        put("buildSystem", "gradle")
        put("target", ":")
        put(
            "nestedRepositories",
            JsonArray(if (nested) listOf(JsonPrimitive("android")) else emptyList()),
        )
        val files =
            mutableListOf(
                buildJsonObject {
                    put("path", "src/main/java/Seed.java")
                    put("module", ":")
                    put("package", "fixture")
                }
            )
        if (nested)
            files += buildJsonObject {
                put("path", "android/src/main/java/Nested.java")
                put("module", ":android")
                put("package", "nested")
            }
        put("files", JsonArray(files))
        put(
            "groups",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("id", "all")
                        put("files", JsonArray(files.indices.map(::JsonPrimitive)))
                    }
                )
            ),
        )
    }

    private fun git(workspace: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", workspace.toString()) + args).start()
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()
        check(process.waitFor() == 0) { error }
        return output
    }
}
