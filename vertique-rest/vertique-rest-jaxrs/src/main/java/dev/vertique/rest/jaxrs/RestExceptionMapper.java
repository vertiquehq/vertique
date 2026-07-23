// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import dev.vertique.core.failure.ContextAwareFailureTranslator;
import dev.vertique.core.failure.FailureMapper;
import dev.vertique.core.failure.FailureTranslator;

/**
 * Layer-specific exception mapper for the REST error pipeline.
 *
 * <p>Extends the shared {@link FailureMapper} to provide hierarchy-aware Throwable-to-Throwable
 * translation at the REST boundary. This mapper is assembled once by {@link RestModule} from all
 * registered {@link RestExceptionMapperCustomizer} contributions, then injected into the
 * {@link ErrorPipeline}.
 *
 * <p>No translations are pre-registered; all entries are contributed via the
 * {@code Set<RestExceptionMapperCustomizer>} Dagger multibinding.
 *
 * @see RestExceptionMapperCustomizer
 * @see ErrorPipeline
 */
public class RestExceptionMapper extends FailureMapper {

    @Override
    public <T extends Throwable> RestExceptionMapper on(Class<T> type, FailureTranslator<T> translator) {
        super.on(type, translator);
        return this;
    }

    @Override
    public <T extends Throwable> RestExceptionMapper on(Class<T> type, ContextAwareFailureTranslator<T> translator) {
        super.on(type, translator);
        return this;
    }
}
