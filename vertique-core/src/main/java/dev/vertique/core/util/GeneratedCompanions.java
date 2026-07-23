// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

import java.lang.reflect.Constructor;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Shared helper that loads and instantiates a generated companion class for a given origin type.
 *
 * <p>Vertique codegen processors emit a companion class per origin type (for example
 * {@code {Contract}_DelayedJobProxy} or {@code {Contract}_WorkflowClientProxy}). At runtime the
 * framework must locate and instantiate that companion. This class consolidates the two-phase lookup
 * logic — absent companion is an expected fallback ({@link Optional#empty()}), present-but-broken
 * companion is a hard failure — so that each factory does not re-implement it.
 *
 * <p>The companion FQN is derived via {@link GeneratedNames#companionFqn(Class, String)}, which
 * handles both top-level and nested origin types by replacing the {@code $} separator with
 * {@code _} to match what the annotation processor emits.
 *
 * <p>See ADR-0070 (delayed-job proxy selection) and ADR-0073 (workflow proxy selection) for the
 * loud-fail rationale: silently degrading to the reflective fallback when a broken generated class
 * is present would hide a build inconsistency and produce a proxy that may behave differently from
 * the one that was generated.
 *
 * @see GeneratedNames
 */
public final class GeneratedCompanions {

    private GeneratedCompanions() {}

    /**
     * Loads and instantiates the generated companion {@code {origin}{suffix}} for the given origin
     * type, deriving the class name via {@link GeneratedNames#companionFqn(Class, String)}.
     *
     * <p>Returns {@link Optional#empty()} when the companion is absent
     * ({@link ClassNotFoundException}), so the caller can fall back to its reflective path. Throws
     * the exception produced by {@code onBroken} when the companion is present but cannot be
     * instantiated — a build inconsistency that must fail loudly, not silently degrade.
     * {@link LinkageError} (static-initializer / {@code NoClassDefFound} at link time), a missing or
     * throwing constructor ({@link ReflectiveOperationException}), and a present companion of the wrong
     * type ({@link ClassCastException} from the {@code origin.cast}) are all treated as
     * present-but-broken.
     *
     * @param <C>          the origin/companion common type
     * @param origin       the origin type the companion was generated from
     * @param suffix       the companion class-name suffix (e.g. {@code "_WorkflowClientProxy"})
     * @param ctorParamTypes the generated constructor's parameter types, in order
     * @param ctorArgs     the arguments to pass to that constructor, in order
     * @param onBroken     produces the exception to throw when the present companion cannot be
     *                     instantiated; receives the companion FQN and the underlying cause
     * @return the instantiated companion cast to {@code C}, or empty when no companion class is
     *     present on the classpath
     * @throws RuntimeException the exception produced by {@code onBroken} when the companion is
     *     present but cannot be instantiated
     */
    public static <C> Optional<C> instantiate(
            Class<C> origin,
            String suffix,
            Class<?>[] ctorParamTypes,
            Object[] ctorArgs,
            BiFunction<String, Throwable, ? extends RuntimeException> onBroken) {
        String fqn = GeneratedNames.companionFqn(origin, suffix);
        try {
            Class<?> generated = Class.forName(fqn, true, origin.getClassLoader());
            Constructor<?> ctor = generated.getDeclaredConstructor(ctorParamTypes);
            return Optional.of(origin.cast(ctor.newInstance(ctorArgs)));
        } catch (ClassNotFoundException notGenerated) {
            // No companion on the classpath — the expected fallback case. (ClassNotFoundException must be
            // caught before the broad ReflectiveOperationException catch below, as it is a subtype.)
            return Optional.empty();
        } catch (ReflectiveOperationException | LinkageError | ClassCastException broken) {
            // Present-but-broken generated class — fail loudly rather than silently degrading to the
            // reflective fallback. Covers: a failing static initializer / link-time NoClassDefFoundError at
            // the eager Class.forName(initialize=true) load step (LinkageError); a missing or throwing
            // constructor (ReflectiveOperationException); and a present companion of the wrong type
            // (ClassCastException from origin.cast — same defect class the per-site registries guard).
            throw onBroken.apply(fqn, broken);
        }
    }
}
