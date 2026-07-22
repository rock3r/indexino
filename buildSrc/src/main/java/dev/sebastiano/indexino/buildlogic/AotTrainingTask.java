// SPDX-License-Identifier: UEL-1.0

package dev.sebastiano.indexino.buildlogic;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(
        because = "AOT cache restoration is not proven reproducible or compatible across runners")
public abstract class AotTrainingTask extends DefaultTask {
    private final ExecOperations execOperations;

    @Inject
    public AotTrainingTask(ExecOperations execOperations) {
        this.execOperations = execOperations;
    }

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getRuntimeImage();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getTargetJdkRoot();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getApplicationJar();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getTrainingFixture();

    @OutputFile
    public abstract RegularFileProperty getAotCache();

    @Input
    public abstract Property<String> getTargetOs();

    @Input
    public abstract Property<String> getTargetArchitecture();

    @Input
    public abstract Property<String> getJbrDigest();

    @Input
    public abstract Property<Long> getNormalizedJarTimestampMillis();

    @Input
    public abstract ListProperty<String> getModules();

    @Input
    public abstract Property<String> getMainClassName();

    @Input
    public abstract Property<String> getClassPath();

    @Input
    public abstract Property<String> getRoastWorkingDirectory();

    @Input
    public abstract Property<String> getFixtureVersion();

    @Input
    public abstract ListProperty<String> getVmArgs();

    @Input
    public abstract ListProperty<String> getTrainingArguments();

    @Input
    public abstract Property<String> getMinimumHeap();

    @Input
    public abstract Property<String> getMaximumHeap();

    @Input
    public abstract Property<String> getJavaExecutableName();

    @Input
    public abstract Property<String> getGitExecutable();

    @Input
    public abstract MapProperty<String, String> getEnvironmentVariables();

    @TaskAction
    public void train() {
        var runtimeInput = getRuntimeImage().get().getAsFile().toPath();
        rejectFullSdkRuntime(runtimeInput);

        var applicationJar = getApplicationJar().get().getAsFile().toPath();
        var expectedTimestamp = FileTime.fromMillis(getNormalizedJarTimestampMillis().get());
        try {
            if (!Files.getLastModifiedTime(applicationJar).equals(expectedTimestamp)) {
                throw new GradleException(
                        "Normalized application JAR mtime changed before AOT training: expected "
                                + expectedTimestamp
                                + ", got "
                                + Files.getLastModifiedTime(applicationJar));
            }

            var staging = getTemporaryDir().toPath().resolve("staging");
            deleteTree(staging);
            Files.createDirectories(staging);
            copyTree(runtimeInput, staging.resolve("runtime"));
            Files.copy(
                    applicationJar,
                    staging.resolve(getClassPath().get()),
                    StandardCopyOption.COPY_ATTRIBUTES);

            var javaSource =
                    getTargetJdkRoot()
                            .get()
                            .getAsFile()
                            .toPath()
                            .resolve("bin")
                            .resolve(getJavaExecutableName().get());
            if (!Files.isRegularFile(javaSource)) {
                throw new GradleException("Missing target JDK launcher: " + javaSource);
            }
            var javaExecutable =
                    staging.resolve("runtime/bin").resolve(getJavaExecutableName().get());
            Files.createDirectories(javaExecutable.getParent());
            Files.copy(
                    javaSource,
                    javaExecutable,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);

            var trainingWorkspace = staging.resolve("training-workspace");
            copyTree(getTrainingFixture().get().getAsFile().toPath(), trainingWorkspace);
            prepareGitFixture(staging, trainingWorkspace);

            var output = getAotCache().get().getAsFile().toPath();
            Files.createDirectories(output.getParent());
            var temporaryOutput =
                    Files.createTempFile(
                            output.getParent(), output.getFileName().toString() + ".", ".tmp");
            Files.delete(temporaryOutput);

            var arguments = new ArrayList<String>();
            arguments.add("-Xms" + getMinimumHeap().get());
            arguments.add("-Xmx" + getMaximumHeap().get());
            arguments.addAll(getVmArgs().get());
            arguments.add("-Duser.home=" + staging.resolve("home"));
            arguments.add("-Djava.io.tmpdir=" + staging.resolve("tmp"));
            arguments.add("-XX:AOTCacheOutput=" + temporaryOutput);
            arguments.add("-cp");
            arguments.add(getClassPath().get());
            arguments.add(getMainClassName().get());
            arguments.addAll(getTrainingArguments().get());

            Files.createDirectories(staging.resolve("home"));
            Files.createDirectories(staging.resolve("tmp"));
            var processEnvironment = declaredEnvironment(staging.resolve("home"));
            execOperations.exec(
                    spec -> {
                        spec.setExecutable(javaExecutable.toFile());
                        spec.setWorkingDir(
                                staging.resolve(getRoastWorkingDirectory().get()).toFile());
                        spec.setArgs(arguments);
                        spec.setEnvironment(processEnvironment);
                    });

            if (!Files.isRegularFile(temporaryOutput) || Files.size(temporaryOutput) == 0L) {
                throw new GradleException(
                        "AOT training completed without a non-empty cache: " + temporaryOutput);
            }
            moveAtomically(temporaryOutput, output);

            if (!Files.getLastModifiedTime(applicationJar).equals(expectedTimestamp)) {
                throw new GradleException("AOT training changed the normalized application JAR mtime");
            }
        } catch (IOException failure) {
            throw new GradleException("Could not assemble target AOT cache", failure);
        }
    }

    private void rejectFullSdkRuntime(Path runtimeInput) {
        var commands = runtimeInput.resolve("bin");
        var executableExtension = getJavaExecutableName().get().endsWith(".exe") ? ".exe" : "";
        for (var command :
                List.of("java", "javac", "jlink", "jdeps", "javap", "jar", "javadoc", "jshell")) {
            var executable = commands.resolve(command + executableExtension);
            if (Files.exists(executable)) {
                throw new GradleException(
                        "Final runtime input must be stripped; found JDK command " + executable);
            }
        }
    }

    private void prepareGitFixture(Path staging, Path workspace) {
        var home = staging.resolve("home");
        try {
            Files.createDirectories(home);
            Files.writeString(staging.resolve("empty-gitconfig"), "");
        } catch (IOException failure) {
            throw new GradleException("Could not prepare hermetic Git configuration", failure);
        }
        var environment = declaredEnvironment(home);
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", staging.resolve("empty-gitconfig").toString());
        environment.put("GIT_AUTHOR_NAME", "Indexino AOT Training");
        environment.put("GIT_AUTHOR_EMAIL", "aot-training@example.invalid");
        environment.put("GIT_AUTHOR_DATE", "2000-01-01T00:00:00Z");
        environment.put("GIT_COMMITTER_NAME", "Indexino AOT Training");
        environment.put("GIT_COMMITTER_EMAIL", "aot-training@example.invalid");
        environment.put("GIT_COMMITTER_DATE", "2000-01-01T00:00:00Z");
        runGit(workspace, environment, "init", "--quiet");
        runGit(workspace, environment, "add", ".");
        runGit(workspace, environment, "commit", "--quiet", "-m", "aot training fixture");
    }

    private void runGit(Path workspace, Map<String, String> environment, String... arguments) {
        execOperations.exec(
                spec -> {
                    spec.setExecutable(getGitExecutable().get());
                    spec.setWorkingDir(workspace.toFile());
                    spec.setArgs(List.of(arguments));
                    spec.setEnvironment(environment);
                });
    }

    private Map<String, String> declaredEnvironment(Path home) {
        var environment = new LinkedHashMap<String, String>();
        environment.put("HOME", home.toString());
        environment.put("USERPROFILE", home.toString());
        environment.put("LC_ALL", "C");
        environment.put("TZ", "UTC");
        getEnvironmentVariables()
                .get()
                .forEach(
                        (name, value) -> {
                            if (!value.isBlank()) {
                                environment.put(name, value);
                            }
                        });
        return environment;
    }

    private void copyTree(Path source, Path destination) throws IOException {
        Files.walkFileTree(
                source,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(
                            Path directory, BasicFileAttributes attributes) throws IOException {
                        var target = destination.resolve(source.relativize(directory).toString());
                        Files.copy(directory, target, StandardCopyOption.COPY_ATTRIBUTES);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                            throws IOException {
                        var target = destination.resolve(source.relativize(file).toString());
                        if (Files.isSymbolicLink(file)) {
                            Files.createSymbolicLink(target, Files.readSymbolicLink(file));
                        } else {
                            Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                            throws IOException {
                        if (failure != null) {
                            throw failure;
                        }
                        var target = destination.resolve(source.relativize(directory).toString());
                        Files.setLastModifiedTime(target, Files.getLastModifiedTime(directory));
                        return FileVisitResult.CONTINUE;
                    }
                });
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

    private void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(
                    source,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
