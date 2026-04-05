package one.wabbit.typeclass

@Target(
    AnnotationTarget.FILE,
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.LOCAL_VARIABLE,
)
@Retention(AnnotationRetention.SOURCE)
public annotation class DebugTypeclassResolution(
    val mode: TypeclassTraceMode = TypeclassTraceMode.FAILURES,
)

public enum class TypeclassTraceMode {
    INHERIT,
    DISABLED,
    FAILURES,
    FAILURES_AND_ALTERNATIVES,
    ALL,
    ALL_AND_ALTERNATIVES,
}
