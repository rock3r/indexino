package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.model.IndexFailure
import dev.sebastiano.indexino.model.IndexFailureCategory
import dev.sebastiano.indexino.model.IndexinoInternalApi

@OptIn(IndexinoInternalApi::class)
internal fun indexinoFailure(
    category: IndexFailureCategory,
    code: String,
    message: String,
    retryable: Boolean,
    cause: Throwable? = null,
): IndexinoException =
    IndexinoException(
        failure =
            IndexFailure.of(
                category = category,
                code = code,
                message = message,
                retryable = retryable,
            ),
        cause = cause,
    )
