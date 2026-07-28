---
title: TODO inside a fence fixture
description: Proves a TODO marker embedded in a fenced code sample still fails the forbidden-token scan.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# TODO inside a fence fixture

The fenced sample below intentionally contains a leftover TODO marker to prove the forbidden-token
scan is not fooled by fencing.

```bash
# TODO: replace this placeholder command before publishing.
echo "example"
```
