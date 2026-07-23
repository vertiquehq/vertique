// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.service;

/**
 * User data returned by the user service.
 *
 * @param id    the user identifier
 * @param name  the user's display name
 * @param email the user's email address
 */
public record UserResponse(String id, String name, String email) {}
