# Vertique Minimal Application Archetype

Generate a minimal Vertique application with its group ID, artifact ID, Java package, and
Vertique version:

```bash
mvn -B archetype:generate \
  -DarchetypeGroupId=dev.vertique \
  -DarchetypeArtifactId=vertique-archetype \
  -DarchetypeVersion=<vertiqueVersion> \
  -DgroupId=<groupId> \
  -DartifactId=<artifactId> \
  -Dpackage=<packageName> \
  -Dversion=0.1.0-SNAPSHOT \
  -DvertiqueVersion=<vertiqueVersion>
```
