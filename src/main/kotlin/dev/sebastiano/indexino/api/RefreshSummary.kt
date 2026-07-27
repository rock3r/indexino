package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.RefreshId

public class RefreshSummary
@IndexinoInternalApi
public constructor(public val id: RefreshId, public val request: RefreshRequest) {
    override fun equals(other: Any?): Boolean =
        other is RefreshSummary && id == other.id && request == other.request

    override fun hashCode(): Int = 31 * id.hashCode() + request.hashCode()

    override fun toString(): String = "RefreshSummary(id=$id, request=$request)"
}
