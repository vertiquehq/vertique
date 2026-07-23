// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.db.resource;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.rest.core.dagger.JaxRsResources;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ext.ExceptionMapper;

/**
 * Dagger module providing JAX-RS resources and exception mappers for the example-db application.
 *
 * <p>Registers:
 * <ul>
 *   <li>{@link ItemResource} — CRUD endpoints for items</li>
 *   <li>{@link UniqueConstraintExceptionMapper} — 409 for unique constraint violations</li>
 *   <li>{@link ConnectionExceptionMapper} — 503 for database connection failures</li>
 *   <li>{@link PageSizeConstraintViolationExceptionMapper} — 400 for page size out of range</li>
 * </ul>
 */
@Module
public class ResourceModule {

    /**
     * Contributes {@link ItemResource} to the JAX-RS resource set.
     *
     * @param resource the item resource instance
     * @return the resource as an {@link Object} for multibinding
     */
    @Provides
    @IntoSet
    @JaxRsResources
    @Singleton
    static Object itemResource(ItemResource resource) {
        return resource;
    }

    /**
     * Contributes {@link UniqueConstraintExceptionMapper} to the exception mapper set.
     *
     * @param mapper the unique constraint exception mapper
     * @return the exception mapper for multibinding
     */
    @Provides
    @IntoSet
    static ExceptionMapper<?> uniqueConstraintMapper(UniqueConstraintExceptionMapper mapper) {
        return mapper;
    }

    /**
     * Contributes {@link ConnectionExceptionMapper} to the exception mapper set.
     *
     * @param mapper the connection exception mapper
     * @return the exception mapper for multibinding
     */
    @Provides
    @IntoSet
    static ExceptionMapper<?> connectionExceptionMapper(ConnectionExceptionMapper mapper) {
        return mapper;
    }

    /**
     * Contributes {@link PageSizeConstraintViolationExceptionMapper} to the exception mapper set.
     *
     * @param mapper the page size constraint violation exception mapper
     * @return the exception mapper for multibinding
     */
    @Provides
    @IntoSet
    static ExceptionMapper<?> pageSizeExceptionMapper(PageSizeConstraintViolationExceptionMapper mapper) {
        return mapper;
    }
}
