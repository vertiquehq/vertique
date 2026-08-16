<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Input Processing Module

> **Status:** Alpha
> **Package:** `dev.vertique.input.processing`
> **Artifact:** `vertique-input-processing`
> **Depends on:** core

Processes decoded input values through declared canonicalization and sanitization policies. The module is transport-neutral: it operates on already-decoded values and has no dependency on any REST or transport layer.

The input-object processor family moves into this module alongside this artifact's introduction; this document names the surface that arrives with that move.

---

## When To Use It

Include this module when an application or framework transport needs decoded input values (request bodies, message payloads, or other structured input) processed through the canonicalization and sanitization policies declared in `dev.vertique.core.sanitization`.

---

## Core Concepts

Input processing walks a decoded input object and applies the canonicalizers and sanitizers its declared policies select, producing a processed value with the same shape. Policies are declared with the annotation model in `dev.vertique.core.sanitization` (in the artifact `dev.vertique:vertique-core`); this module supplies the processing engine that honors them.

---

## Key Classes

### InputObjectProcessor

The processing entry point: accepts a decoded input object and returns the processed result after applying the declared policies.

---

## Extension Points

The generated-processor SPI lets build-time-generated processors replace reflective processing for annotated input types.

---

## Dependencies

- `dev.vertique:vertique-core` — the canonicalization and sanitization contracts and annotation model this module executes.
