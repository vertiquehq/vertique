// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.rest.client.exception.RestClientConfigurationException;

/**
 * Hand-crafted "wrong-type generated accessor" — it is present on the classpath with a public no-arg
 * constructor that succeeds, but it deliberately does <em>not</em> implement {@link BeanParamAccessor}.
 * {@link BeanParamAccessorRegistry} loads and constructs it, then fails at the
 * {@code (BeanParamAccessor<?>)} cast with a {@link ClassCastException}, which the registry must wrap
 * in a {@link RestClientConfigurationException} rather than let escape raw.
 */
public final class WrongTypeBean_BeanParamAccessor {

    /** Public no-arg constructor that succeeds — the failure happens at the cast, not here. */
    public WrongTypeBean_BeanParamAccessor() {
        // No-op; the registry's cast to BeanParamAccessor fails before any method is called.
    }
}
