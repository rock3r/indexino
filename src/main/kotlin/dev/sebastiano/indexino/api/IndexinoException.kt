package dev.sebastiano.indexino.api

import dev.sebastiano.indexino.model.IndexFailure

public class IndexinoException
internal constructor(public val failure: IndexFailure, cause: Throwable?) :
    RuntimeException(failure.message, cause)
