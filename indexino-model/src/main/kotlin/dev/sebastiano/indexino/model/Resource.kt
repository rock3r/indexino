package dev.sebastiano.indexino.model

public class ResourceId
private constructor(
    public val packageName: String?,
    public val type: String,
    public val name: String,
) {
    init {
        require(packageName?.isNotBlank() != false) { "Resource package must not be blank" }
        require(type.isNotBlank()) { "Resource type must not be blank" }
        require(name.isNotBlank()) { "Resource name must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ResourceId &&
                packageName == other.packageName &&
                type == other.type &&
                name == other.name

    override fun hashCode(): Int =
        31 * (31 * (packageName?.hashCode() ?: 0) + type.hashCode()) + name.hashCode()

    override fun toString(): String = "ResourceId(packageName=$packageName, type=$type, name=$name)"

    public companion object {
        @JvmStatic
        public fun of(packageName: String?, type: String, name: String): ResourceId =
            ResourceId(packageName, type, name)
    }
}

public class ResourceDefinition
@IndexinoInternalApi
public constructor(
    public val id: ResourceId,
    public val qualifiers: String,
    public val location: SourceLocation,
) {
    init {
        require(qualifiers.none(Char::isWhitespace)) {
            "Resource qualifiers must not contain whitespace"
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ResourceDefinition &&
                id == other.id &&
                qualifiers == other.qualifiers &&
                location == other.location

    override fun hashCode(): Int =
        31 * (31 * id.hashCode() + qualifiers.hashCode()) + location.hashCode()

    override fun toString(): String =
        "ResourceDefinition(id=$id, qualifiers=$qualifiers, location=$location)"
}

public class ResourceUsage
@IndexinoInternalApi
public constructor(
    public val id: ResourceId,
    public val location: SourceLocation,
    public val language: String,
) {
    init {
        require(language.isNotBlank()) { "Resource usage language must not be blank" }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ResourceUsage &&
                id == other.id &&
                location == other.location &&
                language == other.language

    override fun hashCode(): Int =
        31 * (31 * id.hashCode() + location.hashCode()) + language.hashCode()

    override fun toString(): String =
        "ResourceUsage(id=$id, location=$location, language=$language)"
}

public class ResourceQuery
private constructor(
    public val id: ResourceId?,
    public val packageName: String?,
    public val type: String?,
    public val name: String?,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ResourceQuery &&
                id == other.id &&
                packageName == other.packageName &&
                type == other.type &&
                name == other.name

    override fun hashCode(): Int =
        (((id?.hashCode() ?: 0) * 31 + (packageName?.hashCode() ?: 0)) * 31 +
            (type?.hashCode() ?: 0)) * 31 + (name?.hashCode() ?: 0)

    override fun toString(): String =
        "ResourceQuery(id=$id, packageName=$packageName, type=$type, name=$name)"

    public companion object {
        @JvmStatic public fun all(): ResourceQuery = ResourceQuery(null, null, null, null)

        @JvmStatic
        public fun named(id: ResourceId): ResourceQuery =
            ResourceQuery(id, id.packageName, id.type, id.name)

        @JvmStatic
        public fun of(packageName: String?, type: String?, name: String?): ResourceQuery {
            require(packageName?.isNotBlank() != false) { "Resource package must not be blank" }
            require(type?.isNotBlank() != false) { "Resource type must not be blank" }
            require(name?.isNotBlank() != false) { "Resource name must not be blank" }
            require(type != null || name == null) {
                "Resource name queries require a resource type"
            }
            return ResourceQuery(
                id =
                    if (type != null && name != null) ResourceId.of(packageName, type, name)
                    else null,
                packageName = packageName,
                type = type,
                name = name,
            )
        }
    }
}
