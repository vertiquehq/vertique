// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.redis;

import java.util.List;

/** One asynchronous page from a node-local Redis SCAN cursor. */
public record RedisScanPage(String cursor, List<String> keys, boolean finished) {}
