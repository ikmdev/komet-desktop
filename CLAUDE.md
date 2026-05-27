# komet-desktop

komet-desktop component.

## Build Standards

Files in `.claude/standards/` are build artifacts unpacked from `ike-build-standards`. DO NOT edit or commit them. See the workspace root CLAUDE.md for details.

## Build

```bash
mvn clean verify -DskipTests -T4
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

- GroupId: `dev.ikm.ike`
- Version: `3.0.0-SNAPSHOT`
- Uses `--enable-preview` (Java 25)
- BOM: imports `dev.ikm.ike:ike-bom` for dependency version management

## Prohibited Patterns

- **Never use `maven-antrun-plugin`** — use a proper Maven goal or `exec-maven-plugin`
- **Never use `build-helper-maven-plugin` for multi-execution property chaining** —
  write a proper Maven goal in `ike-maven-plugin`
- **Never embed shell commands inline in POM** — extract to a named script

See `.claude/standards/` (after `mvn validate`) for full standards.
See `CLAUDE-komet-desktop.md` for project-specific notes.
