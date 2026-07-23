// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Annotation processor for generating static REST client proxies and {@code BeanParamAccessor}
 * implementations from {@code @RestClient}-annotated interfaces.
 *
 * <p>The central entry point is {@link dev.vertique.codegen.rest.client.processor.RestClientProcessor}.
 * Supporting classes are organized into sub-packages:
 * <ul>
 *   <li>{@code scan} — APT-side scanners that mirror the runtime
 *       {@code ClientInterfaceScanner} and {@code BeanParamScanner}</li>
 *   <li>{@code validate} — per-rule validators (return type, path placeholder, HTTP verb)</li>
 *   <li>{@code emit} — source emitters for {@code {Client}_RestClientProxy} and
 *       {@code {Bean}_BeanParamAccessor}</li>
 * </ul>
 */
package dev.vertique.codegen.rest.client.processor;
