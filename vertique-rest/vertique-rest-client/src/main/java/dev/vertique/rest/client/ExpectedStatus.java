// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Overrides the expected HTTP response status for a single REST client method.
 *
 * <p>When placed on a method in a {@link RestClient}-annotated interface, the proxy validates the
 * response status code and fails with a {@link dev.vertique.rest.client.exception.RestClientResponseException} if the status does not
 * match. This annotation takes precedence over any default expectation set via the builder.
 *
 * <p>Two mutually exclusive forms are supported:
 * <ul>
 *   <li><b>Exact codes</b> — specify one or more status codes in {@link #value()}:
 *       <pre>{@code
 * @ExpectedStatus({200, 201})
 * Future<Item> createItem(ItemRequest body);
 *       }</pre>
 *   </li>
 *   <li><b>Range</b> — specify inclusive {@link #min()} and exclusive {@link #max()}:
 *       <pre>{@code
 * @ExpectedStatus(min = 200, max = 300)
 * Future<Item> getItem(@PathParam("id") String id);
 *       }</pre>
 *   </li>
 * </ul>
 *
 * <p>If neither form is specified the annotation has no effect.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ExpectedStatus {

    /**
     * One or more exact HTTP status codes to accept. Combined with a logical OR so that any
     * listed code is considered a success.
     *
     * @return the accepted status codes; empty array means this element is unused
     */
    int[] value() default {};

    /**
     * Inclusive lower bound of the acceptable status code range (used together with {@link #max()}).
     *
     * @return the minimum accepted status code, or {@code -1} if unused
     */
    int min() default -1;

    /**
     * Exclusive upper bound of the acceptable status code range (used together with {@link #min()}).
     *
     * @return the exclusive maximum status code, or {@code -1} if unused
     */
    int max() default -1;
}
