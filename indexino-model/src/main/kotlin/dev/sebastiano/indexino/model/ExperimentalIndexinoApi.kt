package dev.sebastiano.indexino.model

@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Experimental Indexino API; semantics may change in a minor release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
public annotation class ExperimentalIndexinoApi
