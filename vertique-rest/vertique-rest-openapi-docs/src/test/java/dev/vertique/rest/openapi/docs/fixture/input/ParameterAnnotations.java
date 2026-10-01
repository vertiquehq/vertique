// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.input;

import io.swagger.v3.oas.annotations.Parameter;
import java.lang.reflect.Proxy;
import java.util.Objects;

/**
 * Creates {@link Parameter} annotation instances with an arbitrary description, the way the binding
 * inventory carries a declared {@code @Parameter} in an input's annotation list.
 *
 * <p>The instance implements the annotation interface: {@code annotationType()} is {@link
 * Parameter}, {@code description()} returns the given text, and every other member returns its
 * declared default. Equality is identity.
 */
public final class ParameterAnnotations {

    private ParameterAnnotations() {}

    /**
     * Creates a {@code @Parameter} carrying only a description.
     *
     * @param description the description, returned unchanged (blank text included)
     * @return the annotation instance
     */
    public static Parameter described(String description) {
        Objects.requireNonNull(description, "description");
        return (Parameter) Proxy.newProxyInstance(
                Parameter.class.getClassLoader(), new Class<?>[] {Parameter.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "annotationType":
                            return Parameter.class;
                        case "description":
                            return description;
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "@" + Parameter.class.getName() + "(description=\"" + description + "\")";
                        default:
                            return method.getDefaultValue();
                    }
                });
    }
}
