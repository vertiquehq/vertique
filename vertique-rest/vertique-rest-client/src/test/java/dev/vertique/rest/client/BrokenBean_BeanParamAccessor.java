// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.rest.client.exception.RestClientConfigurationException;
import java.util.List;

/**
 * Hand-crafted "broken generated accessor" — its public no-arg constructor throws, simulating a
 * faulty annotation-processor output discovered via {@code Class.forName} by
 * {@link BeanParamAccessorRegistry}. The registry must wrap the
 * {@link ReflectiveOperationException} in a {@link RestClientConfigurationException} rather than
 * silently fall back to the reflective accessor.
 */
public final class BrokenBean_BeanParamAccessor implements BeanParamAccessor<BrokenBean> {

    public BrokenBean_BeanParamAccessor() {
        throw new IllegalStateException("test fixture: accessor instantiation always fails");
    }

    @Override
    public Object extract(BrokenBean bean, String fieldName) {
        return null;
    }

    @Override
    public List<String> fieldNames() {
        return List.of();
    }
}
