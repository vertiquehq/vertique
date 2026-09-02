// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * Redis CAS-backed {@code CLUSTERED} {@code RateLimitBackend}, built on Bucket4j's Vert.x Redis
 * integration over {@code vertique-redis-core}'s shared client (contracts/rate-limit-runtime.md,
 * "Redis integration contract").
 */
package dev.vertique.ratelimit.redis;
