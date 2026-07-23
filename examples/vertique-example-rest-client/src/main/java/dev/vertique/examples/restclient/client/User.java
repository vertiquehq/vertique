// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.restclient.client;

import jakarta.annotation.Nullable;

/**
 * Data transfer object representing a user resource.
 *
 * @param id the unique identifier of the user; {@code null} when creating a new user
 * @param name the display name of the user
 * @param email the email address of the user; optional
 */
public record User(
        @Nullable String id, String name, @Nullable String email) {}
