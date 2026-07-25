package dev.sebastiano.indexino.api

public class BuildSystem private constructor(public val value: String) {
    public companion object {
        @JvmField public val BAZEL: BuildSystem = BuildSystem("BAZEL")
        @JvmField public val GRADLE: BuildSystem = BuildSystem("GRADLE")
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is BuildSystem && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "BuildSystem(value=$value)"
}
