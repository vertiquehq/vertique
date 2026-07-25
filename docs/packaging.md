<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Packaging

> **Applies to:** `@VertiqueApp` applications (standalone Vertique apps using the bootstrap
> verticle). See below for the opt-out path for applications that intentionally use a custom
> verticle instead of `@VertiqueApp`.

Vertique applications that use `@VertiqueApp` share a **uniform entry point** —
`dev.vertique.launcher.VertiqueApplication` — so there is no per-application main class or
`Main-Verticle` manifest attribute to maintain. This uniformity makes container packaging via
**Jib** straightforward and removes the `META-INF/services/` merging complexity that plagued
maven-shade fat-JARs.

---

## Maven application setup

### Recommended: public application parent

Inherit `vertique-app-parent` and declare only runtime capabilities. The parent imports
`vertique-bom`, targets Java 21, and configures Dagger together with the complete Vertique
processor facade:

```xml
<parent>
    <groupId>dev.vertique</groupId>
    <artifactId>vertique-app-parent</artifactId>
    <version>0.0.0-SNAPSHOT</version> <!-- replace with the released Vertique version -->
    <relativePath/>
</parent>

<dependencies>
    <dependency>
        <groupId>dev.vertique</groupId>
        <artifactId>vertique-application</artifactId>
    </dependency>
    <dependency>
        <groupId>dev.vertique</groupId>
        <artifactId>vertique-rest-jaxrs</artifactId>
    </dependency>
</dependencies>
```

Add or remove runtime dependencies as the application capabilities change. Do not mirror those
choices with processor-leaf dependencies or processor-path entries; the facade is intentionally a
stable, closed ledger, and processors ignore source sets that do not use their annotations.

The runtime-only rule has one explicit source-API exception. Applications that write
`@NoAutoWire` or `@ConditionalOnProperty` import those source-retained annotations from
`vertique-codegen-core`, so they add that artifact as a compile-time-only dependency:

```xml
<dependency>
    <groupId>dev.vertique</groupId>
    <artifactId>vertique-codegen-core</artifactId>
    <scope>provided</scope>
</dependency>
```

This does not select a processor and does not add codegen classes to the runtime classpath.

### Custom parent: BOM plus processor facade

If an organization requires its own parent, import the Vertique BOM and configure Maven Compiler
Plugin with exactly Dagger and the Vertique facade. Versions are intentionally omitted from
`annotationProcessorPaths`; Maven Compiler Plugin resolves them from dependency management.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>dev.vertique</groupId>
            <artifactId>vertique-bom</artifactId>
            <version>0.0.0-SNAPSHOT</version> <!-- replace with the released Vertique version -->
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.15.0</version>
            <configuration>
                <release>21</release>
                <annotationProcessorPaths>
                    <path>
                        <groupId>com.google.dagger</groupId>
                        <artifactId>dagger-compiler</artifactId>
                    </path>
                    <path>
                        <groupId>dev.vertique</groupId>
                        <artifactId>vertique-codegen-all</artifactId>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### Explicit Lombok opt-in

Lombok is not active by default. Applications that deliberately use Lombok opt in with both a
provided dependency and an appended processor path:

```xml
<dependencies>
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <scope>provided</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths combine.children="append">
                    <path>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### Disable annotation processing

A module that intentionally uses no generated Dagger or Vertique code can disable annotation
processing explicitly:

```xml
<properties>
    <maven.compiler.proc>none</maven.compiler.proc>
</properties>
```

This is an escape hatch, not an application default. It disables every annotation processor,
including Dagger, so a module that references generated types will fail to compile.

---

## The `@VertiqueApp` entry shape

The minimal component declaration for a standalone application:

```java
// src/main/java/com/example/AppComponent.java
@VertiqueApp                           // (1) triggers the generated factory + SPI file
@Singleton
@Component(modules = {
    VertxModule.class,
    CoreLifecycleStepsModule.class,    // (2) contributes CONFIGURE (Jackson) + VALIDATE steps
    // ... application modules ...
    DeployerModule.class,
    AppModule.class
})
interface AppComponent extends VertiqueApplicationComponent {}  // (3) exposes lifecycle inputs
```

Three things are required:

1. **`@VertiqueApp`** — tells `vertique-codegen-application` to generate
   `AppComponentVertiqueComponentFactory` and write `META-INF/services/dev.vertique.core.VertiqueComponentFactory`.
   No hand-written factory or SPI file is needed.
2. **`CoreLifecycleStepsModule`** — contributes the `CONFIGURE`-phase `JacksonConfigureStep`
   (applies `ObjectMapperCustomizer` multibinding to the Vert.x `DatabindCodec` mapper) and the
   `VALIDATE`-phase `ComposeValidationStep`. Omitting this module means Jackson is not configured
   via the framework lifecycle and compose-validators do not run.
3. **`extends VertiqueApplicationComponent`** — exposes `startupSteps()`, `shutdownSteps()`, and
   `verticleDeploymentManager()` so `VertiqueApplicationBootstrap` can drive the eight-phase
   lifecycle without knowing the concrete component type.

All examples in this repository use `@VertiqueApp`. Examples with a **standalone run path** also
use Jib + `exec-maven-plugin` for container and local-JVM packaging. The reference implementations
are `vertique-example-hello` and `vertique-example-services` in the `examples/` directory.

> The **headless test-only** `vertique-example-workflow-order-fulfillment` example has an
> `@VertiqueApp` component but **no Jib or exec packaging**. It has no standalone entry point and
> is exercised exclusively through its integration tests.

---

## Jib image build

**`jib-maven-plugin`** is the standard packaging tool for `@VertiqueApp` applications. Repository
examples inherit its version from the internal `vertique-parent`. The public
`vertique-app-parent` deliberately does not manage packaging tools, so external applications must
pin Jib themselves or obtain its version from their organization's parent:

```xml
<plugin>
    <groupId>com.google.cloud.tools</groupId>
    <artifactId>jib-maven-plugin</artifactId>
    <version>3.5.1</version>
    <configuration>
        <from>
            <image>eclipse-temurin:21-jre</image>   <!-- slim JRE-only base -->
        </from>
        <to>
            <image>my-app</image>                   <!-- local image name -->
        </to>
        <container>
            <!-- uniform Vertique entry point — no Main-Verticle needed -->
            <mainClass>dev.vertique.launcher.VertiqueApplication</mainClass>
        </container>
    </configuration>
</plugin>
```

Jib is **not bound to any Maven lifecycle phase** — building a container image on every `mvn verify`
would add unnecessary overhead. Images are built explicitly:

```bash
# Build into the local Docker daemon (requires a running daemon)
./mvnw -pl examples/vertique-example-hello -am jib:dockerBuild

# Build to an OCI tar archive (no daemon required — works in daemon-free CI)
./mvnw -pl examples/vertique-example-hello -am jib:buildTar

# Push to a remote registry (no daemon required)
./mvnw -pl examples/vertique-example-hello -am jib:build
```

Jib builds layered images with the application's own classes in a thin top layer and dependency
JARs in slower-moving lower layers. This means adding a new framework module version or bumping a
third-party library only invalidates the affected dependency layer, not the whole image.

`META-INF/services/` files from all dependency JARs land intact in separate classpath entries
(Jib operates on the unpacked classpath), so no `ServicesResourceTransformer` is needed and
ServiceLoader discovery works correctly.

---

## Local run via exec-maven-plugin

`exec-maven-plugin` lets you run the application directly on your local JVM without building a
container image:

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <configuration>
        <mainClass>dev.vertique.launcher.VertiqueApplication</mainClass>
    </configuration>
</plugin>
```

```bash
# Compile and start (config from src/main/resources/ + env/sys properties)
./mvnw compile exec:java -pl examples/vertique-example-hello -am

# Pass a config overlay with --conf
./mvnw compile exec:java -pl examples/vertique-example-hello -am \
    -Dexec.args='--conf {"http":{"port":8080}}'
```

The main class is the same as the Jib container — there is no separate "dev" entry point.

---

## Opt-out for custom-verticle applications

Applications that intentionally use a custom verticle instead of `@VertiqueApp` — for example, a
CLI tool or a host bridge that manages its own startup choreography — set the system property
`-Dvertique.bootstrap.verticle=false`. This makes `VertiqueApplication.verticleSupplier()` return
`null`, deferring verticle resolution to the upstream Vert.x launcher's standard CLI
positional-argument path.

When using `exec-maven-plugin`, supply the property via `<systemProperties>` and pass the
custom verticle FQN as a positional argument:

```xml
<!-- pom.xml fragment for a custom-verticle application -->
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>exec-maven-plugin</artifactId>
    <configuration>
        <mainClass>dev.vertique.launcher.VertiqueApplication</mainClass>
        <!-- opt out of the @VertiqueApp bootstrap verticle; pass the custom verticle class
             as a CLI arg so the upstream launcher deploys it via standard positional-argument
             resolution. -->
        <systemProperties>
            <systemProperty>
                <key>vertique.bootstrap.verticle</key>
                <value>false</value>
            </systemProperty>
        </systemProperties>
        <arguments>
            <argument>com.example.CustomVerticle</argument>
        </arguments>
    </configuration>
</plugin>
```

```bash
./mvnw -pl my-module exec:java
```

The property is case-insensitive (`false`, `FALSE`, `False` all opt out). This opt-out is a
framework feature for applications that have a genuine reason to manage their own verticle outside
the `@VertiqueApp` lifecycle (for example, a host bridge that drives its own startup choreography).

---

## Migration recipe

To migrate an existing application from `maven-shade` + hand-written `MainVerticle` to `@VertiqueApp`
+ Jib:

1. **Adopt the public application parent and add `vertique-application` to `<dependencies>`**
   (if not already present). If the project must retain a custom parent, use the BOM-plus-facade
   recipe above. In both cases application modules declare runtime capabilities only.

2. **Add `@VertiqueApp` and `extends VertiqueApplicationComponent` to the `@Component`.**
   Include `CoreLifecycleStepsModule` in the module list if not already present.

3. **Delete the hand-written `MainVerticle`** (and its corresponding `VerticleDeployment` binding
   if you had one). The `VertiqueBootstrapVerticle` in `vertique-launcher` takes its place
   automatically.

4. **Delete the `META-INF/services/dev.vertique.core.VertiqueComponentFactory`** file if you wrote
   one manually — `vertique-codegen-application` generates it now.

5. **Replace `maven-shade-plugin` with `jib-maven-plugin`** using the configuration shown above.
   Remove the shade `<transformer>` and `<filter>` sections.

6. **Update `exec-maven-plugin`** to point at `dev.vertique.launcher.VertiqueApplication` (it may
   already, if the example was previously using `VertiqueApplication`).

7. **Remove `-Dvertique.bootstrap.verticle=false`** from `exec-maven-plugin` arguments if present.

---
