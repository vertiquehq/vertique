// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.customresponse.resource;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.examples.customresponse.CategorizedExceptionMapper;
import dev.vertique.examples.customresponse.DigestFilter;
import dev.vertique.rest.core.dagger.JaxRsResources;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import jakarta.ws.rs.ext.ExceptionMapper;

/**
 * Dagger module that contributes JAX-RS resources, custom exception mappers, and
 * lifecycle hooks to their respective multibinding sets.
 *
 * <p>Add a new {@code @Provides @IntoSet @JaxRsResources} method for each additional
 * resource class. Exception mappers and lifecycle hooks follow the same pattern.
 */
@Module
public class ResourceModule {

    /**
     * Contributes {@link ItemResource} to the {@code @JaxRsResources} multibinding.
     *
     * @param resource the resource instance provided by Dagger
     * @return the resource as an {@link Object} for the multibinding set
     */
    @Provides
    @IntoSet
    @JaxRsResources
    Object itemResource(ItemResource resource) {
        return resource;
    }

    /**
     * Contributes {@link CategorizedExceptionMapper} to the {@code Set<ExceptionMapper<?>>}
     * multibinding so the framework registers it in the
     * {@link dev.vertique.rest.jaxrs.ExceptionMapperRegistry}.
     *
     * <p>Because {@link CategorizedExceptionMapper} implements {@code ExceptionMapper<Throwable>},
     * it replaces the framework's default mapper for all exceptions.
     *
     * @param mapper the mapper instance provided by Dagger
     * @return the mapper as a typed {@link ExceptionMapper} for the multibinding set
     */
    @Provides
    @IntoSet
    ExceptionMapper<?> categorizedExceptionMapper(CategorizedExceptionMapper mapper) {
        return mapper;
    }

    /**
     * Contributes {@link DigestFilter} to the {@code Set<RequestInterceptor>}
     * multibinding for request digest validation and response digest computation.
     *
     * @param filter the filter instance provided by Dagger
     * @return the interceptor for the multibinding set
     */
    @Provides
    @IntoSet
    RequestInterceptor digestFilter(DigestFilter filter) {
        return filter;
    }
}
