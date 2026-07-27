package dev.sebastiano.indexino.model

public sealed interface PluginFactValue {
    public class Text private constructor(public val value: String) : PluginFactValue {
        public companion object {
            @JvmStatic public fun of(value: String): Text = Text(value)
        }

        override fun equals(other: Any?): Boolean = other is Text && value == other.value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "Text(value=$value)"
    }

    public class Integer private constructor(public val value: Long) : PluginFactValue {
        public companion object {
            @JvmStatic public fun of(value: Long): Integer = Integer(value)
        }

        override fun equals(other: Any?): Boolean = other is Integer && value == other.value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "Integer(value=$value)"
    }

    public class Bool private constructor(public val value: Boolean) : PluginFactValue {
        public companion object {
            @JvmStatic public fun of(value: Boolean): Bool = Bool(value)
        }

        override fun equals(other: Any?): Boolean = other is Bool && value == other.value

        override fun hashCode(): Int = value.hashCode()

        override fun toString(): String = "Bool(value=$value)"
    }

    public class TextList private constructor(public val values: List<String>) : PluginFactValue {
        public companion object {
            @JvmStatic
            public fun of(values: List<String>): TextList {
                require(values.size <= MAX_TEXT_LIST_SIZE) { "Plugin fact text list is too large" }
                return TextList(values.toList())
            }
        }

        override fun equals(other: Any?): Boolean = other is TextList && values == other.values

        override fun hashCode(): Int = values.hashCode()

        override fun toString(): String = "TextList(values=$values)"
    }

    public class Struct private constructor(public val fields: Map<String, PluginFactValue>) :
        PluginFactValue {
        public companion object {
            @JvmStatic
            public fun of(fields: Map<String, PluginFactValue>): Struct {
                require(fields.size <= MAX_STRUCT_FIELDS) { "Plugin fact struct is too large" }
                return Struct(fields.toMap())
            }
        }

        override fun equals(other: Any?): Boolean = other is Struct && fields == other.fields

        override fun hashCode(): Int = fields.hashCode()

        override fun toString(): String = "Struct(fields=$fields)"
    }

    public companion object {
        public const val MAX_TEXT_LIST_SIZE: Int = 256
        public const val MAX_STRUCT_FIELDS: Int = 64
        public const val MAX_DEPTH: Int = 4
    }
}
