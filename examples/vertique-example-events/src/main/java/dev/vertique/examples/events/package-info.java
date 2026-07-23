// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * End-to-end example proving that {@code vertique-codegen-events} generates a
 * {@code GeneratedEventsModule} with {@code @Provides @IntoSet ObserverRegistration} providers that
 * aggregate observers across modules via Dagger multibinding, and that firing an {@code Event<T>}
 * invokes all matched observers at runtime.
 */
package dev.vertique.examples.events;
