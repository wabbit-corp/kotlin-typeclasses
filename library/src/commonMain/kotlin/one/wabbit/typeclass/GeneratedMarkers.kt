package one.wabbit.typeclass

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class GeneratedTypeclassWrapper

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
@Repeatable
public annotation class GeneratedTypeclassInstance(
    val typeclassId: String,
    val targetId: String,
    val kind: String,
    val payload: String = "",
)
