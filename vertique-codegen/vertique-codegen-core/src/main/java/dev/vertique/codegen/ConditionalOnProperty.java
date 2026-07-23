// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a generated service-contract implementation or JAX-RS resource as active only when a
 * runtime configuration property matches an expected value.
 *
 * <p>The Vertique annotation processors ({@code vertique-codegen-services} and
 * {@code vertique-codegen-jaxrs}) read this annotation at compile time and emit conditional
 * registration logic into the generated Dagger wiring. At runtime the generated code evaluates
 * the condition against the live {@code @VertxConfig JsonObject} before contributing an
 * implementation to the service registry or the JAX-RS route table.
 *
 * <p>This annotation is {@link Repeatable}: apply it multiple times on the same type to express
 * an AND of conditions — all conditions must match for the type to be activated. The container
 * annotation is {@link ConditionalOnProperties}.
 *
 * <p>This annotation has {@link RetentionPolicy#SOURCE} retention — it is consumed only during
 * compilation and never reaches the runtime classpath or the compiled {@code .class} file.
 *
 * <p>It lives in {@code vertique-codegen-core} (not in a specific codegen processor module) so
 * that user code can import and use it as a compile-scope dependency without pulling the
 * processor itself onto the normal compile classpath. Declare it {@code provided} or
 * {@code compileOnly}:
 *
 * <pre>{@code
 * <dependency>
 *     <groupId>dev.vertique</groupId>
 *     <artifactId>vertique-codegen-core</artifactId>
 *     <scope>provided</scope>
 * </dependency>
 * }</pre>
 *
 * <p>Example — activate sandbox implementation only when {@code sandboxEnabled=true}:
 * <pre>{@code
 * @ConditionalOnProperty(name = "sandboxEnabled")
 * public class UserServiceSandbox implements UserService { ... }
 * }</pre>
 *
 * <p>Example — require two properties to both match (AND semantics):
 * <pre>{@code
 * @ConditionalOnProperty(name = "feature.a.enabled")
 * @ConditionalOnProperty(name = "mode", havingValue = "sandbox", matchIfMissing = false)
 * public class SandboxAFeatureImpl implements AFeatureService { ... }
 * }</pre>
 *
 * @see ConditionalOnProperties
 * @see <a href="https://github.com/vertiquehq/vertique/blob/main/vertique-codegen/vertique-codegen-services/src/main/resources/META-INF/vertique/module.md">
 *      Service Codegen Guide</a>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@Repeatable(ConditionalOnProperties.class)
@Documented
public @interface ConditionalOnProperty {

    /**
     * The dotted-path name of the configuration property to match, e.g. {@code "sandboxEnabled"}
     * or {@code "feature.payment.enabled"}.
     *
     * @return the property path; must not be empty
     */
    String name();

    /**
     * The string value the property must equal for the condition to be satisfied.
     *
     * <p>Comparison uses {@link String#valueOf(Object)} on the resolved property value, so
     * {@code true} / {@code false} booleans and integer/long scalars can be matched by their
     * string representation (e.g. {@code havingValue = "true"}, {@code havingValue = "42"}).
     *
     * <p>Defaults to {@code "true"} to match the common boolean-flag case.
     *
     * @return the expected string value; defaults to {@code "true"}
     */
    String havingValue() default "true";

    /**
     * Whether the condition is satisfied when the property is absent from the configuration.
     *
     * <p>When {@code false} (the default), a missing property causes the condition to fail and
     * the annotated type is not activated. When {@code true}, a missing property is treated as
     * a satisfied condition and evaluation continues to the next condition (if any).
     *
     * @return {@code true} if a missing property counts as a match; defaults to {@code false}
     */
    boolean matchIfMissing() default false;
}
