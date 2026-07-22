// SPDX-License-Identifier: UEL-1.0

package dev.sebastiano.indexino.buildlogic;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(
        because = "Cache restoration is not proven to preserve the application JAR filesystem mtime")
public abstract class NormalizedJar extends Jar {
    public NormalizedJar() {
        getOutputs().upToDateWhen(task -> false);
    }

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInputJar();

    @Input
    public abstract Property<Long> getNormalizedTimestampMillis();

    @Override
    @TaskAction
    protected void copy() {
        var input = getInputJar().get().getAsFile().toPath();
        var output = getArchiveFile().get().getAsFile().toPath();
        var timestamp = FileTime.fromMillis(getNormalizedTimestampMillis().get());
        Path temporaryOutput = null;
        try {
            Files.createDirectories(output.getParent());
            temporaryOutput =
                    Files.createTempFile(
                            output.getParent(), output.getFileName().toString() + ".", ".tmp");
            Files.copy(input, temporaryOutput, StandardCopyOption.REPLACE_EXISTING);
            var posixAttributes =
                    Files.getFileAttributeView(temporaryOutput, PosixFileAttributeView.class);
            if (posixAttributes != null) {
                posixAttributes.setPermissions(
                        EnumSet.of(
                                PosixFilePermission.OWNER_READ,
                                PosixFilePermission.OWNER_WRITE,
                                PosixFilePermission.GROUP_READ,
                                PosixFilePermission.OTHERS_READ));
            }
            Files.setLastModifiedTime(temporaryOutput, timestamp);
            try {
                Files.move(
                        temporaryOutput,
                        output,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporaryOutput, output, StandardCopyOption.REPLACE_EXISTING);
            }
            var actualTimestamp = Files.getLastModifiedTime(output);
            if (!actualTimestamp.equals(timestamp)) {
                throw new GradleException(
                        "Could not set normalized JAR mtime: expected "
                                + timestamp
                                + ", got "
                                + actualTimestamp);
            }
        } catch (IOException failure) {
            if (temporaryOutput != null) {
                try {
                    Files.deleteIfExists(temporaryOutput);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw new GradleException("Could not normalize application JAR mtime", failure);
        }
    }
}
