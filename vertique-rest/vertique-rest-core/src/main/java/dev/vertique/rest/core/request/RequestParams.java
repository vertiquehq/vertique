// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class or record as a composite request parameter object whose fields
 * carry JAX-RS parameter annotations ({@code @QueryParam}, {@code @PathParam},
 * {@code @HeaderParam}, {@code @CookieParam}, {@code @FormParam}).
 *
 * <p>When a resource method parameter's type is annotated with {@code @RequestParams},
 * the framework automatically extracts and populates it from the request — no
 * {@code @BeanParam} annotation is needed on the method parameter:
 *
 * <pre>{@code
 * @RequestParams
 * public record MyFilter(
 *         @QueryParam("status") @Nullable String status,
 *         @HeaderParam("X-Tenant") @Nullable String tenant) {}
 *
 * // In the resource method — no @BeanParam needed:
 * @GET
 * public Future<List<Item>> list(MyFilter filter) { ... }
 * }</pre>
 *
 * <p>This is the class-level equivalent of JAX-RS {@link jakarta.ws.rs.BeanParam @BeanParam}
 * (which can only target method parameters). Framework types like
 * {@link dev.vertique.rest.core.pagination.OffsetPageRequest}
 * and {@link dev.vertique.rest.core.pagination.CursorPageRequest} use this annotation.
 *
 * @see dev.vertique.rest.core.pagination.OffsetPageRequest
 * @see dev.vertique.rest.core.pagination.CursorPageRequest
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequestParams {}
