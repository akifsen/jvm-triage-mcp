# Local verification

On 2026-09-14, Windows / Temurin 21.0.12+8 / Maven 3.9.16:

- `mvnw.cmd -B -ntp spotless:apply verify -Pintegration`: five core and three integration tests passed, zero skips.
- Independent source-only copy: `mvnw.cmd -B -ntp verify -Pintegration` passed the same eight tests, without sibling source dependencies.
- Packaged JfrFixture generated two actual synthetic JDK recordings. Packaged CLI/session summarized and compared them; outputs are in `evidence/summary.json` and `evidence/comparison.json`. These illustrate evidence structure, not a performance benchmark. JFR binary files are intentionally ignored.
- Actual contention was observed. This demo did not force GC or socket activity; their zero counts must not be read as proof of absence.

The sandbox initially denied the JVM temporary recording directory; the demo succeeded with normal local filesystem access. GitHub CI and Linux execution have not been performed. No live application or private recording was accessed.
