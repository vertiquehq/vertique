// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.service;

/** Result returned by the composition probe after the handler body is reached. */
public record CompositionProbeResult(String key, int invocation) {}
