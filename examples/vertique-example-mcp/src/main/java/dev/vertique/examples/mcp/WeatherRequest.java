// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp;

import jakarta.validation.constraints.NotBlank;

/** Structured, validated input for the weather tool. */
public record WeatherRequest(@NotBlank String locationName) {}
