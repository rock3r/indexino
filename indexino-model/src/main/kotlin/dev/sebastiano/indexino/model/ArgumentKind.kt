package dev.sebastiano.indexino.model

public class ArgumentKind private constructor(public val value: String) {
    public companion object {
        @JvmField public val VALUE: ArgumentKind = ArgumentKind("VALUE")
        @JvmField public val LAMBDA: ArgumentKind = ArgumentKind("LAMBDA")
        @JvmField public val TRAILING_LAMBDA: ArgumentKind = ArgumentKind("TRAILING_LAMBDA")
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is ArgumentKind && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "ArgumentKind(value=$value)"
}
