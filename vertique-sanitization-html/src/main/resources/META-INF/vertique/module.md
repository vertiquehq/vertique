<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Sanitization HTML Module

> **Status:** Stable
> **Package:** `dev.vertique.sanitization.html`
> **Artifact:** `vertique-sanitization-html`
> **Depends on:** core, sanitization

Provides four HTML sanitizers built on the OWASP Java HTML Sanitizer. They are ordinary `Sanitizer` implementations that `@Sanitize` names by class; the module ships no Dagger module and no binding.

---

## When To Use It

Add this dependency when an application declares `@Sanitize` with one of the HTML sanitizers on input it binds. An application that sanitizes no HTML does not need it, and so does not carry the OWASP library.

The sanitizers apply through `SanitizationModule` from `dev.vertique:vertique-sanitization`, which an application installs as usual.

---

## Key Classes

| Class | Effect |
|-------|--------|
| `StripAllHtmlSanitizer` | Strips all HTML tags and attributes and returns plain text: entities are decoded, `script` and `style` content is dropped, block-level element boundaries become newlines, and the result is trimmed |
| `BasicHtmlSanitizer` | Allows `<p>`, `<br>`, `<em>`, `<i>`, `<strong>`, `<b>`, `<ul>`, `<ol>`, `<li>`; strips everything else |
| `LinksHtmlSanitizer` | Allows `BasicHtml` elements plus `<a href>` (http, https and mailto schemes only; `rel="nofollow"` is added to links) |
| `RichTextHtmlSanitizer` | Allows `LinksHtml` elements plus `u`, `s`, `h1`-`h6`, `blockquote`, `pre`, `code`, `table`, `thead`, `tbody`, `tr`, `th`, `td`, `hr`, `span`, `div`, `sub`, `sup` and `<img>` (`src`, `alt`, `width`, `height`); the `class` attribute is allowed on every allowed element |

`BasicHtmlSanitizer`, `LinksHtmlSanitizer` and `RichTextHtmlSanitizer` use a static `PolicyFactory`; `StripAllHtmlSanitizer` uses a per-call text extractor. All four are stateless and thread-safe, and each has a public no-arg constructor.

---

## How To Use It

Add the dependency, then name the class in `@Sanitize`. There is no Dagger module to install and nothing to bind; the engine creates each sanitizer through its public no-arg constructor on first use and reuses it.

```xml
<dependency>
    <groupId>dev.vertique</groupId>
    <artifactId>vertique-sanitization-html</artifactId>
</dependency>
```

```java
import dev.vertique.sanitization.html.BasicHtmlSanitizer;

public record CreateUserRequest(
    @Sanitize(BasicHtmlSanitizer.class)
    String bio
) {}
```

---

## Extension Points

To share or configure an instance, contribute a `SanitizerBinding` keyed by the sanitizer class, as for any custom sanitizer:

```java
@Provides @IntoSet
static SanitizerBinding basicHtml() {
    return new SanitizerBinding(BasicHtmlSanitizer.class, new BasicHtmlSanitizer());
}
```

A binding keyed by one of these classes replaces the default instance; it does not conflict with it.

---

## Dependencies

- `dev.vertique:vertique-core`
- `dev.vertique:vertique-sanitization`
- `com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer` (OWASP Java HTML Sanitizer, exported)
