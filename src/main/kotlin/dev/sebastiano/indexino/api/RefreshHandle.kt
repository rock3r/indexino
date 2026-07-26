@file:Suppress("RedundantSuspendModifier")

package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.model.IndexinoInternalApi
import dev.sebastiano.indexino.model.RefreshId

public class RefreshHandle
@IndexinoInternalApi
public constructor(public val id: RefreshId, private val result: RefreshResult) {
    public suspend fun await(): RefreshResult = result
}
