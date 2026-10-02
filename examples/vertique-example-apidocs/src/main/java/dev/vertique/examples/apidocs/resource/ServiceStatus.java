// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.apidocs.resource;

/**
 * The status of the service.
 *
 * @param status {@code UP} while the service is running
 */
public record ServiceStatus(String status) {}
