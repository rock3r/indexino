// SPDX-License-Identifier: UEL-1.0

package dev.sebastiano.indexino.buildlogic;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.BasicFileAttributes;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "The host ditto archive output is not proven byte-reproducible")
public abstract class MacDittoArchive extends DefaultTask {
    private final ExecOperations execOperations;

    @Inject
    public MacDittoArchive(ExecOperations execOperations) {
        this.execOperations = execOperations;
        getOutputs().upToDateWhen(task -> false);
    }

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputArchive();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getNormalizedJar();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getAotCache();

    @Input
    public abstract Property<String> getDittoExecutable();

    @OutputFile
    public abstract RegularFileProperty getOutputArchive();

    @TaskAction
    public void archive() {
        var staging = getTemporaryDir().toPath().resolve("staging");
        var input = getInputArchive().get().getAsFile();
        var normalizedJar = getNormalizedJar().get().getAsFile().toPath();
        var aotCache = getAotCache().get().getAsFile().toPath();
        var output = getOutputArchive().get().getAsFile().toPath();
        RuntimeException taskFailure = null;
        try {
            deleteTree(staging);
            Files.createDirectories(staging);
            execOperations.exec(
                    spec -> {
                        spec.setExecutable(getDittoExecutable().get());
                        spec.args("--norsrc", "-x", "-k", input, staging.toFile());
                    });

            var installation = staging.resolve("indexino");
            if (!Files.isDirectory(installation)) {
                throw new GradleException("Missing indexino archive root after ditto extraction");
            }
            var stagedJar = installation.resolve("indexino-cli.jar");
            Files.copy(
                    normalizedJar,
                    stagedJar,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            if (Files.mismatch(normalizedJar, stagedJar) != -1L
                    || !Files.getLastModifiedTime(normalizedJar)
                            .equals(Files.getLastModifiedTime(stagedJar))) {
                throw new GradleException("Could not restore the exact normalized application JAR");
            }
            var stagedCache = installation.resolve("runtime/lib/server/classes.jsa");
            Files.copy(
                    aotCache,
                    stagedCache,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            Files.setPosixFilePermissions(
                    stagedCache, PosixFilePermissions.fromString("rw-r--r--"));
            if (Files.mismatch(aotCache, stagedCache) != -1L) {
                throw new GradleException("Could not restore the exact macOS AOT cache");
            }

            Files.createDirectories(output.getParent());
            Files.deleteIfExists(output);
            execOperations.exec(
                    spec -> {
                        spec.setExecutable(getDittoExecutable().get());
                        spec.args(
                                "--norsrc",
                                "-c",
                                "-k",
                                "--keepParent",
                                installation.toFile(),
                                output.toFile());
                    });
            if (!Files.isRegularFile(output) || Files.size(output) == 0L) {
                throw new GradleException("ditto did not produce a non-empty archive: " + output);
            }
        } catch (IOException failure) {
            taskFailure =
                    new GradleException(
                            "Could not finalize the macOS native archive with ditto", failure);
            throw taskFailure;
        } catch (RuntimeException failure) {
            taskFailure = failure;
            throw failure;
        } finally {
            try {
                deleteTree(staging);
            } catch (IOException cleanupFailure) {
                if (taskFailure != null) {
                    taskFailure.addSuppressed(cleanupFailure);
                } else {
                    throw new GradleException(
                            "Could not clean the macOS archive staging directory", cleanupFailure);
                }
            }
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(
                root,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                            throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                            throws IOException {
                        if (failure != null) {
                            throw failure;
                        }
                        Files.delete(directory);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }
}
