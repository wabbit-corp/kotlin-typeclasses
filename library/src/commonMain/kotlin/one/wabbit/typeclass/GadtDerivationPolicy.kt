package one.wabbit.typeclass

public enum class GadtDerivationMode {
    SURFACE_TRUSTED,
    CONSERVATIVE_ONLY,
}

@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class GadtDerivationPolicy(
    val mode: GadtDerivationMode,
)
