package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.model.IndexFailure
import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.RefreshId

public sealed interface RefreshEvent {
    public val refreshId: RefreshId
}

public class RefreshStarted
@IndexinoInternalApi
public constructor(override val refreshId: RefreshId) : RefreshEvent {
    override fun equals(other: Any?): Boolean =
        other is RefreshStarted && refreshId == other.refreshId

    override fun hashCode(): Int = refreshId.hashCode()

    override fun toString(): String = "RefreshStarted(refreshId=$refreshId)"
}

public class RefreshFailed
@IndexinoInternalApi
public constructor(override val refreshId: RefreshId, public val failure: IndexFailure) :
    RefreshEvent {
    override fun equals(other: Any?): Boolean =
        other is RefreshFailed && refreshId == other.refreshId && failure == other.failure

    override fun hashCode(): Int = 31 * refreshId.hashCode() + failure.hashCode()

    override fun toString(): String = "RefreshFailed(refreshId=$refreshId, failure=$failure)"
}

public class RefreshStopped
@IndexinoInternalApi
public constructor(override val refreshId: RefreshId, public val resumable: Boolean) :
    RefreshEvent {
    override fun equals(other: Any?): Boolean =
        other is RefreshStopped && refreshId == other.refreshId && resumable == other.resumable

    override fun hashCode(): Int = 31 * refreshId.hashCode() + resumable.hashCode()

    override fun toString(): String = "RefreshStopped(refreshId=$refreshId, resumable=$resumable)"
}

public class RefreshCompleted
@IndexinoInternalApi
public constructor(override val refreshId: RefreshId, public val result: RefreshResult) :
    RefreshEvent {
    override fun equals(other: Any?): Boolean =
        other is RefreshCompleted && refreshId == other.refreshId && result == other.result

    override fun hashCode(): Int = 31 * refreshId.hashCode() + result.hashCode()

    override fun toString(): String = "RefreshCompleted(refreshId=$refreshId, result=$result)"
}
