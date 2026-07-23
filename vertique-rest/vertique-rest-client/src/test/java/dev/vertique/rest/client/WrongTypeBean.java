// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.rest.client.exception.RestClientConfigurationException;
import jakarta.ws.rs.QueryParam;

/**
 * Test fixture used by {@code BeanParamAccessorRegistryTest} to verify that a present generated
 * accessor of the <em>wrong type</em> (one that does not implement {@link BeanParamAccessor})
 * surfaces as a {@link RestClientConfigurationException} via the registry's {@code Class.forName}
 * lookup, rather than escaping as a raw {@link ClassCastException}. Paired with
 * {@code WrongTypeBean_BeanParamAccessor} (also in this test source root), which constructs
 * successfully but does not implement {@link BeanParamAccessor}.
 */
public class WrongTypeBean {
    @QueryParam("v")
    public String value;
}
