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

## Key Facts

- GroupId: `dev.ikm.komet`
- ArtifactId: `komet-desktop`
- Uses Maven 4 with POM 4.1.0 (root="true", no parent inheritance for version)
- JReleaser assembles jlink image and native installers
- App version derived from build timestamp: `build.year.build.monthday.build.hhmm`
- `build.hhmm` (not `build.time`) is the numeric HHmm component — `build.time` is the human-readable timestamp from the parent
