<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# MCP Core

> **Status:** Alpha
> **Package:** `dev.vertique.mcp.lifecycle`, `dev.vertique.mcp.interceptor`
> **Artifact:** `vertique-mcp-core`
> **Depends on:** `vertique-core`, `vertique-security-core`, `jakarta.annotation-api`

`vertique-mcp-core` owns the stable public API for Model Context Protocol lifecycle facts and
neutral per-request observation. It contains immutable terminal and completion events, their
outcome classifications, and extension interfaces. It has no HTTP router, protocol parser,
handler invocation, or runtime composition.

## Lifecycle facts

`McpRequestTerminalEvent` records the single logical settlement of a request. Construct it through
its named factories (`success`, `toolError`, `rejected`, `failed`, or `cancelled`) so that outcome,
result type, and error classification remain internally consistent. `McpRequestCompletedEvent`
records the later transport outcome after the response was written, disconnected, reset, or failed.

Both records are immutable, validate their temporal and protocol-state invariants, and retain only
bounded protocol facts. They never carry request bodies, headers, credentials, exception text, or
arbitrary client-provided method or tool names.

## Observation extensions

Contribute `McpRequestLifecycleObserver` through Dagger set multibinding. The server opens one
`McpRequestObservation` per contributed observer for each request, invokes terminal observation at
logical settlement, and invokes completion observation after the transport settles. Observers are
observe-only, synchronous, non-blocking, and failure-isolated by the server runtime.

Applications that only need a post-transport callback can contribute an
`McpRequestCompletedListener`. Listener order is unspecified and listener failures cannot alter a
request outcome.

`McpTraceContext` is the optional normalized W3C trace reference associated with a terminal
observation. It carries no baggage.

## Dependency boundary

This module consumes only the public core correlation snapshot and security snapshot types. Runtime
dispatch, HTTP integration, tool registration, authorization enforcement, and observability adapters
belong to separately packaged modules and must not be introduced here.
