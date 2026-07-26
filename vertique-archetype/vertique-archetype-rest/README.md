# Vertique REST Application Archetype

Generate a Vertique REST application with its group ID, artifact ID, Java package, and
Vertique version:

```bash
mvn -B archetype:generate \
  -DarchetypeGroupId=dev.vertique \
  -DarchetypeArtifactId=vertique-archetype-rest \
  -DarchetypeVersion=<vertiqueVersion> \
  -DgroupId=<groupId> \
  -DartifactId=<artifactId> \
  -Dpackage=<packageName> \
  -Dversion=0.1.0-SNAPSHOT \
  -DvertiqueVersion=<vertiqueVersion>
```

The generated project depends on exactly `dev.vertique:vertique-starter-rest` and
`dev.vertique:vertique-launcher` in production scope, and declares its test libraries explicitly.
