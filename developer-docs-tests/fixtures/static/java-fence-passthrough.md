---
title: Java fence passthrough fixture
description: Proves a fenced Java sample with an import, a nested generic, and link-shaped text passes the renderer, placeholder, and link scans.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Java fence passthrough fixture

The fenced sample below intentionally contains a Java `import` statement, a nested generic type,
and text shaped like a broken Markdown link. None of these are flagged, because they sit inside a
fenced code block that every prose-only scan skips.

```java
import dev.vertique.example.Thing;

Optional<Response<String>> handle() {
  // See [broken link](does-not-exist.md) for details - fenced, so never checked.
  return Optional.empty();
}
```
