// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.typeclass

/**
 * Admission modes for GADT-like derivation fragments.
 */
public enum class GadtDerivationMode {
    /**
     * Trust the typeclass surface enough to admit the broader currently
     * supported fragment.
     */
    SURFACE_TRUSTED,

    /**
     * Restrict derivation to the conservative head-preserving fragment.
     */
    CONSERVATIVE_ONLY,
}

/**
 * Overrides the default GADT-like derivation admission policy for a class or
 * transported type parameter.
 *
 * This annotation is only relevant to the derivation machinery; code that does
 * not rely on GADT-like derivation behavior can ignore it.
 *
 * @property mode requested admission mode
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class GadtDerivationPolicy(
    val mode: GadtDerivationMode,
)
