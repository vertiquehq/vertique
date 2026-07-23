// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

/**
 * A domain type with no native {@code ParamConverter} registered in
 * {@code dev.vertique.rest.core.convert.BuiltinParamConverters} (unlike {@code String}, which has a
 * built-in identity converter that would always win over any JAX-RS
 * {@code jakarta.ws.rs.ext.ParamConverterProvider} in {@code ParamConversionResolver}'s
 * native-registry-first resolution order).
 *
 * <p>Used by {@link AnnotationSensitiveParamConverterCodegenTest} as the target type for its
 * annotation-sensitive {@code ParamConverterProvider} fixture, so the provider is the only possible
 * source of a converter — proving the test genuinely exercises the JAX-RS provider bridge rather than
 * being satisfied by a native converter before the provider is ever consulted. Declared as a public,
 * top-level, no-arg-constructible class (rather than a nested test class) so it is referenceable by
 * fully-qualified name from {@code ProcessorTestHarness}'s inline-compiled fixture sources, which
 * cannot see nested classes of the test file that invokes the harness.
 */
public final class TestNoNativeConverterType {

    private final String value;

    /**
     * Creates an instance wrapping the given raw string value.
     *
     * @param value the wrapped string value
     */
    public TestNoNativeConverterType(String value) {
        this.value = value;
    }

    /**
     * Returns the wrapped string value.
     *
     * @return the wrapped value
     */
    public String value() {
        return value;
    }
}
