// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.application;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a named REST application on an interface (Beta).
 *
 * <p>The annotated interface is never instantiated. The annotation is processed at compile time by
 * the JAX-RS annotation processor in {@code vertique-codegen-jaxrs} (and retained at runtime),
 * which validates the declaration and emits one native registration for it. Declaration rules
 * ({@code vertique-codegen-jaxrs}, compile time):
 *
 * <ul>
 *   <li>{@code @RestApplication} must annotate an interface; a class, enum, record, or annotation
 *       type fails compilation.
 *   <li>{@link #name()} is required, must match {@code [a-z0-9][a-z0-9_-]{0,63}}, must not be the
 *       reserved {@code none} or {@code null}, and must be unique among one compilation unit's
 *       declarations.
 *   <li>{@link #path()} is required and is normalized and validated against the application path
 *       grammar.
 *   <li>Exactly one of a non-empty {@link #resources()} and {@link #discover()} {@code true} must
 *       be set.
 *   <li>{@link #discover()} {@code true} is permitted for at most one declaration per compilation
 *       unit — the sole registration in that unit.
 *   <li>The declaring interface and every listed resource class must be accessible from the
 *       generated module's package.
 * </ul>
 *
 * <p>The runtime registration contract for the emitted registrations and the composition of
 * declared applications into mounted routers live in {@code vertique-rest-jaxrs}.
 *
 * <p>This annotation is Beta and outside the Stable promise of {@code vertique-rest-core}: it may
 * change in a later release, and only with a migration note.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface RestApplication {

    /**
     * The application's name; required, must match {@code [a-z0-9][a-z0-9_-]{0,63}} (at most 64
     * characters), and must not be the reserved {@code none} or {@code null}.
     *
     * @return the application name
     */
    String name();

    /**
     * The application's mount path; required, normalized and validated against the application
     * path grammar at compile time.
     *
     * @return the application path, as written
     */
    String path();

    /**
     * The application's resource classes; exactly one of a non-empty {@link #resources()} and
     * {@link #discover()} {@code true} must be set.
     *
     * @return the listed resource classes, in the order written; empty when {@link #discover()}
     *     is used instead
     */
    Class<?>[] resources() default {};

    /**
     * Whether this application's resources are discovered at startup rather than listed
     * explicitly. Only one declaration per compilation unit may set this to {@code true}.
     *
     * @return {@code true} to discover resources instead of listing them in {@link #resources()}
     */
    boolean discover() default false;

    /**
     * The application's OpenAPI contract location, carried as written.
     *
     * @return the OpenAPI contract path, or {@code ""} (the default) to mean the global
     *     {@code jaxrs.openapiPath}
     */
    String openapiPath() default "";
}
