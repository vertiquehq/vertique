// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.rest.client.exception.RestClientException;
import dev.vertique.rest.client.exception.RestClientResponseException;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link java.net.URI} method parameter as the per-invocation absolute request-URI
 * override.
 *
 * <p>When present, this parameter supplies the complete target URI for the request, replacing the
 * entire {@code baseUrl + @Path} template resolution. All other rest-client behavior (interceptors,
 * timeout, retry, circuit breaker, exception mapping, header/cookie handling, body serialization)
 * still applies.
 *
 * <h3>Constraints</h3>
 *
 * <ul>
 *   <li>Parameter type must be {@link java.net.URI}.
 *   <li>At most one {@code @Url} parameter per method.
 *   <li>Mutually exclusive with any {@code jakarta.ws.rs} parameter annotation on the same
 *       parameter.
 *   <li>{@code @DefaultValue} is not allowed on a {@code @Url} parameter.
 *   <li>Methods with {@code @Url} must not have any effective {@code @Path} (class-level or
 *       method-level) and must not have any {@code @PathParam} on other parameters, including
 *       nested {@code @BeanParam} fields.
 * </ul>
 *
 * <h3>Runtime validation</h3>
 *
 * <p>The URI must be absolute, use {@code http} or {@code https}, have a valid host (ASCII or
 * Punycode-encoded — use {@link java.net.IDN#toASCII(String)} for internationalized domain names),
 * and must not contain a fragment. Invalid values throw {@link RestClientException} before transport
 * dispatch.
 *
 * <h3>Security (SSRF)</h3>
 *
 * <p>The framework enforces basic URI sanity (scheme, host, no fragment) but does <strong>not</strong>
 * restrict network destinations — localhost, private IPs, and cloud metadata endpoints are all
 * reachable. When the URI originates from user input, external APIs, webhook registrations, or any
 * untrusted source, validate it against an allowlist before passing it to this parameter. Using
 * unvalidated user-supplied URLs is a Server-Side Request Forgery (SSRF) risk.
 *
 * <p>The full request URI (including query parameters) is exposed in
 * {@link RestClientResponseException#requestContext()}, interceptor callbacks, and log output. If
 * query parameters contain tokens or credentials, take care to mask sensitive components before
 * logging.
 *
 * <h3>Example</h3>
 *
 * <pre>{@code
 * @RestClient("webhook")
 * interface WebhookClient {
 *     @GET
 *     Future<Void> call(@Url URI url, @HeaderParam("X-Payment-Id") String paymentId);
 * }
 * }</pre>
 *
 * @see RestClient
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Url {}
