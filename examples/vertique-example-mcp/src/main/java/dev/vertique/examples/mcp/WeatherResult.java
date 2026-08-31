// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.mcp;

import jakarta.validation.constraints.NotBlank;

/** Structured result returned by the weather tool. */
public record WeatherResult(
        @NotBlank String locationName,
        int temperatureCelsius,
        @NotBlank String condition) {}
