# Vertique Application Archetypes

`dev.vertique:vertique-archetype` is a `packaging=pom` aggregator. It groups the Vertique
application Maven archetypes and is never published as a usable coordinate — always generate from
one of its children.

| Archetype | Generates |
|---|---|
| [`dev.vertique:vertique-archetype-rest`](vertique-archetype-rest/README.md) | A Vertique REST application on `vertique-starter-rest` |
| [`dev.vertique:vertique-archetype-services`](vertique-archetype-services/README.md) | A headless Vertique event-bus services application on `vertique-starter-services` |
| [`dev.vertique:vertique-archetype-rest-postgresql`](vertique-archetype-rest-postgresql/README.md) | A PostgreSQL-backed Vertique REST application on `vertique-starter-rest` and `vertique-starter-postgresql` |

See the child's README for its generation command and required properties.
