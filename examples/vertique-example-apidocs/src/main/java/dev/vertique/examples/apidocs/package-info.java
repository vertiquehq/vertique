// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Root package of the example-apidocs application.
 *
 * <p>Two declared REST applications publish their OpenAPI documents at runtime:
 * {@link dev.vertique.examples.apidocs.PublicApi} at {@code /api/public}, whose document every caller
 * may read, and {@link dev.vertique.examples.apidocs.ManagementApi} at {@code /api/mgmt}, whose
 * document only an authenticated {@code admin} may read. Authentication uses JWT bearer tokens
 * verified with a key read from configuration. A Redoc page at {@code /apidocs/ui/} renders the
 * public document.
 *
 * <p>Key classes:
 * <ul>
 *   <li>{@link dev.vertique.examples.apidocs.AppComponent} — Dagger root component</li>
 *   <li>{@link dev.vertique.examples.apidocs.AppModule} — the JWT provider, the HTTP verticle
 *       deployment, and the documentation page</li>
 *   <li>{@link dev.vertique.examples.apidocs.PublicApi} and
 *       {@link dev.vertique.examples.apidocs.ManagementApi} — the application declarations</li>
 *   <li>{@link dev.vertique.examples.apidocs.ApiDocsUiMount} — the documentation page and its
 *       content security policy</li>
 * </ul>
 */
package dev.vertique.examples.apidocs;
