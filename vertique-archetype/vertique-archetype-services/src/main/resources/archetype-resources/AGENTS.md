# Working on this application

This project was generated from a Vertique archetype. The framework composes through this
project's own Dagger component (`AppComponent`), wiring the declared starter module(s) together
with the application-owned `AppModule`. Dagger resolves that object graph at compile time, so a
missing or malformed binding fails the build rather than the running process; a smaller set of
wiring mistakes — for example, a security policy with no matching mechanism module — instead fail
at application startup. Build early and often, so a wiring mistake surfaces close to the change
that introduced it.

## Skills

Install the Vertique agent skills for version-matched module knowledge. The companion
[vertique-skills](https://github.com/vertiquehq/vertique-skills) repository ships agent skills for
Claude Code, Codex CLI, GitHub Copilot, and Cursor. Its knowledge skill reads the canonical module
reference inside the exact Vertique artifact versions this project resolves, so its answers stay
matched to this project's own build rather than to whichever documentation is newest.

## Canonical references

Every consumable Vertique JAR carries a `META-INF/vertique/module.md` resource: version-matched
reference documentation for that artifact's API, configuration, and extension points. The broader
developer documentation corpus is published at https://vertique.dev/docs.

## Verification

- `mvn -ntp compile` — fast structural feedback: Dagger and the framework's own annotation
  processors surface a wiring mistake here, ahead of any test run.
- `mvn -ntp verify` — the full suite, including integration tests. Some generated projects'
  integration tests need a reachable Docker daemon; this project's own README states its
  prerequisites.
- `mvn -ntp exec:java` — runs the application locally.

## Configuration reality

Runtime configuration resolves from a `config/` directory relative to the process's own working
directory, not from a packaged classpath resource — an edit made only on the classpath will not
reach a locally running instance.
