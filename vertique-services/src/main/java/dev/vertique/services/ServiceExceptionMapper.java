// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import dev.vertique.core.failure.ContextAwareFailureTranslator;
import dev.vertique.core.failure.FailureMapper;
import dev.vertique.core.failure.FailureTranslator;

/**
 * Layer-specific exception mapper for the service dispatch pipeline.
 *
 * <p>Extends the shared {@link FailureMapper} to provide hierarchy-aware {@code Throwable}-to-{@code
 * Throwable} translation at the service dispatch boundary. This mapper is assembled once by
 * {@link DispatchModule} from all registered {@link ServiceExceptionMapperCustomizer} contributions,
 * then injected into the service dispatch chain via {@link ServiceDeploymentManager}.
 *
 * <p>No translations are pre-registered; all entries are contributed via the
 * {@code Set<ServiceExceptionMapperCustomizer>} Dagger multibinding.
 *
 * @see ServiceExceptionMapperCustomizer
 * @see ServiceDeploymentManager
 */
public class ServiceExceptionMapper extends FailureMapper {

    @Override
    public <T extends Throwable> ServiceExceptionMapper on(Class<T> type, FailureTranslator<T> translator) {
        super.on(type, translator);
        return this;
    }

    @Override
    public <T extends Throwable> ServiceExceptionMapper on(Class<T> type, ContextAwareFailureTranslator<T> translator) {
        super.on(type, translator);
        return this;
    }
}
