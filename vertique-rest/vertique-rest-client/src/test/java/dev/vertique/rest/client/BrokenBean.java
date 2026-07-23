// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.rest.client.exception.RestClientConfigurationException;
import jakarta.ws.rs.QueryParam;

/**
 * Test fixture used by {@code BeanParamAccessorRegistryTest} to verify that a broken generated
 * accessor (whose no-arg constructor throws) surfaces as a {@link RestClientConfigurationException}
 * via the registry's {@code Class.forName} lookup. Paired with
 * {@code BrokenBean_BeanParamAccessor} (also in this test source root) whose constructor throws
 * unconditionally.
 */
public class BrokenBean {
    @QueryParam("v")
    public String value;
}
