package dev.sebastiano.indexino.plugin.api

import dev.sebastiano.indexino.model.BasicFactQueries
import dev.sebastiano.indexino.model.BasicFactSchemaVersion
import dev.sebastiano.indexino.model.Finding
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.PluginFactEntry
import dev.sebastiano.indexino.model.PluginFactSchemaVersion
import dev.sebastiano.indexino.model.PluginFactValue
import dev.sebastiano.indexino.model.PluginId
import dev.sebastiano.indexino.model.QueryOptions
import dev.sebastiano.indexino.model.QueryPage
import dev.sebastiano.indexino.model.SourceFile
import dev.sebastiano.indexino.model.SourceOriginId
import dev.sebastiano.indexino.model.SourceRange

@OptIn(IndexinoInternalApi::class)
public fun interface IndexinoPluginProvider {
    public fun install(registrar: IndexinoPluginRegistrar): Unit
}

public class PluginDescriptor
private constructor(
    public val id: PluginId,
    public val version: String,
    public val factSchemaVersion: PluginFactSchemaVersion,
    public val requiredBasicFactSchema: BasicFactSchemaVersion,
    public val producedNamespaces: Set<String>,
    public val displayName: String,
) {
    public companion object {
        @JvmStatic
        public fun of(
            id: PluginId,
            version: String,
            factSchemaVersion: PluginFactSchemaVersion,
            requiredBasicFactSchema: BasicFactSchemaVersion,
            producedNamespaces: Set<String>,
            displayName: String,
        ): PluginDescriptor {
            require(version.isNotBlank()) { "Plugin version must not be blank" }
            require(displayName.isNotBlank()) { "Plugin display name must not be blank" }
            require(producedNamespaces.all { it.isNotBlank() }) {
                "Plugin namespaces must not be blank"
            }
            return PluginDescriptor(
                id,
                version,
                factSchemaVersion,
                requiredBasicFactSchema,
                producedNamespaces.toSet(),
                displayName,
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        other is PluginDescriptor &&
            id == other.id &&
            version == other.version &&
            factSchemaVersion == other.factSchemaVersion &&
            requiredBasicFactSchema == other.requiredBasicFactSchema &&
            producedNamespaces == other.producedNamespaces &&
            displayName == other.displayName

    override fun hashCode(): Int =
        listOf(
                id,
                version,
                factSchemaVersion,
                requiredBasicFactSchema,
                producedNamespaces,
                displayName,
            )
            .hashCode()

    @Suppress("EqualityMembers")
    override fun toString(): String =
        "PluginDescriptor(id=$id, version=$version, displayName=$displayName)"
}

@Suppress("EmptyDefaultConstructor")
@IndexinoInternalApi
public class IndexinoPluginRegistrar public constructor() {
    private var descriptor: PluginDescriptor? = null
    private val fileAnalyzers: MutableList<FileAnalyzerV1> = mutableListOf()
    private val checks: MutableList<IndexinoCheckV1> = mutableListOf()
    private val postProcessors: MutableList<PostProcessorV1> = mutableListOf()

    public fun plugin(descriptor: PluginDescriptor): Unit {
        this.descriptor = descriptor
    }

    public fun fileAnalyzer(analyzer: FileAnalyzerV1): Unit {
        fileAnalyzers.add(analyzer)
    }

    public fun check(check: IndexinoCheckV1): Unit {
        checks.add(check)
    }

    public fun postProcessor(processor: PostProcessorV1): Unit {
        postProcessors.add(processor)
    }

    @IndexinoInternalApi public fun descriptor(): PluginDescriptor = checkNotNull(descriptor)

    @IndexinoInternalApi public fun fileAnalyzers(): List<FileAnalyzerV1> = fileAnalyzers.toList()

    @IndexinoInternalApi public fun checks(): List<IndexinoCheckV1> = checks.toList()

    @IndexinoInternalApi
    public fun postProcessors(): List<PostProcessorV1> = postProcessors.toList()
}

@OptIn(IndexinoInternalApi::class)
public interface FileAnalyzerV1 {
    public val id: String

    public suspend fun analyze(context: FileAnalysisContextV1): Unit
}

public enum class PostProcessLevelV1 {
    SHARD,
    COMPOSITE,
}

@OptIn(IndexinoInternalApi::class)
public interface PostProcessorV1 {
    public val id: String
    public val level: PostProcessLevelV1
    public val readsBasicFactFamilies: Set<String>
    public val readsPluginNamespaces: Set<String>

    public suspend fun process(context: PostProcessContextV1): Unit
}

@Suppress("EqualityMembers")
@IndexinoInternalApi
public class PostProcessContextV1
public constructor(
    public val queries: BasicFactQueries,
    public val facts: PluginFactSinkV1,
    public val originId: SourceOriginId? = null,
    private val active: () -> Boolean,
) {
    public constructor(
        facts: PluginFactSinkV1,
        originId: SourceOriginId? = null,
        active: () -> Boolean,
    ) : this(
        queries = UnavailableBasicFactQueries,
        facts = facts,
        originId = originId,
        active = active,
    )

    public constructor(
        facts: PluginFactSinkV1,
        active: () -> Boolean,
    ) : this(facts = facts, originId = null, active = active)

    public fun ensureActive(): Unit = check(active()) { "Plugin post-processing was cancelled" }

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "PostProcessContextV1(queries=$queries, originId=$originId)"
}

@OptIn(IndexinoInternalApi::class)
public interface IndexinoCheckV1 {
    public val id: String

    public suspend fun run(context: CheckContextV1): List<Finding>
}

public interface PluginFactSinkV1 {
    public suspend fun put(key: String, value: PluginFactValue): Unit

    public suspend fun putAt(key: String, range: SourceRange?, value: PluginFactValue): Unit
}

public interface PluginFactViewV1 {
    public suspend fun get(key: String): PluginFactValue?

    public suspend fun entries(prefix: String, options: QueryOptions): QueryPage<PluginFactEntry>
}

@Suppress("EqualityMembers")
@IndexinoInternalApi
public class FileAnalysisContextV1
public constructor(
    public val file: SourceFile,
    public val sourceText: String,
    public val facts: PluginFactSinkV1,
    private val active: () -> Boolean,
) {
    public fun ensureActive(): Unit = check(active()) { "Plugin analysis was cancelled" }

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "FileAnalysisContextV1(file=$file)"
}

@Suppress("EqualityMembers")
@IndexinoInternalApi
public class CheckContextV1
public constructor(
    public val queries: BasicFactQueries,
    public val facts: PluginFactViewV1,
    private val active: () -> Boolean,
) {
    public fun ensureActive(): Unit = check(active()) { "Plugin check was cancelled" }

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "CheckContextV1(queries=$queries)"
}
