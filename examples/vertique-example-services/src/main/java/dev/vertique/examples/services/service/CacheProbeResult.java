// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.examples.services.service;

/** Result returned by the cache probe, including the propagated caller and handler count. */
public record CacheProbeResult(String actor, int invocation) {}
