# komet-desktop — Project Notes

<!-- Migrated from CLAUDE.md by ws:init.
     This file is for hand-authored, project-specific information.
     Commit this file to git. -->

# Komet Desktop

Desktop packaging and installer for Komet using jlink/jpackage via JReleaser.

## Build Standards

Files in `.claude/standards/` are build artifacts unpacked from `ike-build-standards`. DO NOT edit or commit them. See the workspace root CLAUDE.md for details.

## Build

```bash
mvn clean verify -DskipTests -T 1C
```

Auto-activates the `fast-dev` profile (skips installer + codesign + notarize)
unless env var `IKE_RELEASE` is set. This is the default for IntelliJ runs,
CLI dev builds, and any contributor without Apple Developer ID / Windows
code-signing credentials. Produces a runnable jlink image at
`target/kometRuntimeImage/`.

To build a signed installer (releases), run via:

```bash
op run --env-file=~/.config/ike/release.env -- mvn clean verify -DskipTests
```

The env file sets `IKE_RELEASE=1` alongside the signing credentials, which
deactivates `fast-dev` and runs the full jpackage + codesign + notarize path.

## Running from IntelliJ with the plugin layer

The `Komet (Dev)` and `Komet (Dev Debug)` run configurations
(`.run/`) start `KometLauncher` from the IDE's module path in seconds,
instead of the 8–12 minute build that assembles the jlink image. They
load the same plugin layer as the image — the Claude assistant
(`komet-claude-plugin`), `komet-grpc-plugin`, `complex-clause-plugin`,
`rocks-kb-engine` and their companions — through a Maven before-launch
task that runs, from the workspace root,

```bash
mvn -pl komet-desktop clean:clean@clean-dev-plugins dependency:copy@stage-dev-plugins dependency:copy@stage-dev-plugin-service-loader
```

That restages `target/plugins` and `target/plugin-service-loader` from
the local Maven repository on every launch (about two seconds), and
`App.resolveRuntimeDirectory` probes `<user.dir>/target/<name>` after
`./<name>` and `../<name>`, the same way `IkeServiceManager` already
probes `target/plugin-service-loader`. The clean step first empties both
directories so a renamed snapshot (`7-foo-SNAPSHOT` beside `7-SNAPSHOT`
after a sibling switch) cannot leave two versions of one module in the
layer. The three `fetch-*` executions remain the image path.

Because the plugins arrive from the repository, an edit to a plugin is
not in the next IDE run until it is installed:

```bash
mvn -pl komet-claude-plugin -DskipTests install
```

The log confirms the layer: `Initializing plugin system from:
.../komet-desktop/target/plugins`, then `Created plugin module layer
'Standard plugins directory' with 11 modules` listing `komet.claude`. A
`Failed to create module layer` names the missing module — install that
plugin (or the dependency it names) rather than widening the boot layer.

## Tests against the jlinked image

Integration tests (`*IT`, failsafe) run after `package` has built the
image, and against it. `RuleCompilationInImageIT` has the image's own
`java` run `RuleCompilationProbe`, which compiles rule source against
Komet's types and fires the rules: add a rule to rules in use, list
them, replace an edited rule, refuse source that does not compile. Its
last cases put a rule source in use through Komet's own rule service
(`EvreteRulesService`), as the running application does. In
the image those types exist only inside the image, with no jar for a
compiler to read, so a test on Maven's class path proves nothing about
it. The test fails, rather than being skipped, when there is no image.

To run it alone against an image already built, from the workspace
root:

```bash
mvn -pl komet-desktop jacoco:prepare-agent-integration@jacoco-prepare-agent-integration failsafe:integration-test failsafe:verify -Dit.test=RuleCompilationInImageIT
```

The jacoco goal comes first because it sets `failsafeArgLine`; without
it the test JVM is started with a literal `@{failsafeArgLine}` and
dies. `-Dkomet.image.directory=<image>` points the test at an image
built elsewhere.

## Key Facts

- GroupId: `dev.ikm.komet`
- ArtifactId: `komet-desktop`
- Uses Maven 4 with POM 4.1.0 (root="true", no parent inheritance for version)
- JReleaser assembles jlink image and native installers
- App version derived from build timestamp: `build.year.build.monthday.build.hhmm`
- `build.hhmm` (not `build.time`) is the numeric HHmm component — `build.time` is the human-readable timestamp from the parent
