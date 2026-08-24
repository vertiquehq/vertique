// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

/** Stable identity for a Redis primary used by topology-aware maintenance. */
public record RedisPrimaryNode(String id) {}
