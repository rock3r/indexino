package dev.sebastiano.indexino.model

@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal Indexino host API; not for application or plugin authors.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
public annotation class IndexinoInternalApi
