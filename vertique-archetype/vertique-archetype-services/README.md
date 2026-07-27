# Vertique Services Application Archetype

`dev.vertique:vertique-archetype-services` generates a headless Vertique event-bus services
application on the services application starter.

The generated project depends on exactly `dev.vertique:vertique-starter-services` and
`dev.vertique:vertique-launcher` in production scope, and declares its test libraries explicitly.
It contributes no HTTP edge: the only deployment entry it owns is the management verticle.
