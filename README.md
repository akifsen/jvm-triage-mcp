# JVM Triage MCP

Repository: https://github.com/akifsen/jvm-triage-mcp. Local verification results are documented below; hosted CI status must be checked in GitHub Actions.

Turn a local JFR recording into deterministic evidence about GC pauses, monitor contention and socket waits. The CLI and official Java MCP SDK server call the same bounded JDK RecordingFile analysis core. It does not attach to or modify live JVMs.

## Quick start

Java 21 and Maven Wrapper; no Docker or external service needed:

```powershell
.\mvnw.cmd -B verify -Pintegration
java -cp target/jvm-triage-mcp-0.1.0-SNAPSHOT.jar tr.com.akifsen.triage.JfrFixture recordings
'{"tool":"summarize_recording","arguments":{"file":"contended.jfr"}}' | java -Xmx128m -jar target/jvm-triage-mcp-0.1.0-SNAPSHOT.jar --session
'{"tool":"compare_recordings","arguments":{"before":"contended.jfr","after":"independent.jfr"}}' | java -Xmx128m -jar target/jvm-triage-mcp-0.1.0-SNAPSHOT.jar --session
```

Linux/macOS uses `./mvnw`; invoke the CLI with `summarize_recording '{"file":"contended.jfr"}'` arguments or pipe envelopes using printf. The Windows session examples avoid PowerShell 5.1 native-argument quote loss. The fixture records real JDK monitor contention, then a version using independent locks. All data is synthetic. Generated `.jfr` files are ignored by Git. Compare source stacks and counts, not an assumed universally zero-contention result: JVM runtime events can also occur.

## Tools

| Tool | Input | Output |
|---|---|---|
| summarize_recording | relative file | SHA-256, observed event interval, event schemas/counts/durations, top contention stacks and warnings |
| top_contention_sites | relative file | Up to eight sites with count/duration and bounded stacks |
| compare_recordings | before/after relative files | Both evidence reports plus explicit comparability limits |

For an IDE stdio connection, run `java -Xmx128m -jar <absolute-built-JAR-path> --stdio` and configure `JFR_ROOT` to an existing recording directory. Default root is `recordings` relative to process working directory. No IDE/global configuration is modified. CLI accepts `<tool> '<JSON>'` or `--session` newline envelopes such as `{"tool":"summarize_recording","arguments":{"file":"contended.jfr"}}`. MCP stdout is protocol-only; CLI stdout is result JSON; diagnostics go to stderr. CLI exits 2 on invalid/denied/failed analysis, 0 on a report; inspect `truncated` and warnings for partial data.

## Evidence, not a diagnosis verdict

`jdk.GCPhasePause` measures pause events without double-counting nested phase event types. `jdk.JavaMonitorEnter` supplies contention sites. `jdk.SocketRead`/`jdk.SocketWrite` report observed wait durations, not necessarily network faults. Counts and summed nanoseconds are retained per type. Overlapping thread/event durations are not wall-clock CPU utilization.

Schema presence is reported separately from observed count. Zero events does not mean the behavior never occurred; recording settings and thresholds can hide it. The interval is the min start/max end **among events actually read**, not an inferred full recording header interval. Missing stacks are explicit. Comparing different workloads, durations, JDKs or recording settings cannot establish causality.

## Architecture and limits

CLI/SDK → Triage → canonical-root/file policy → bounded private snapshot → JDK streaming RecordingFile reader → bounded aggregate evidence. No arbitrary shell commands, live process access or external URL resolution is exposed. Paths must be relative, within JFR_ROOT, regular `.jfr` files; symlink escapes are rejected. Files are copied with NOFOLLOW on the final component before parsing, then temporary snapshots are removed.

Two admitted operations, no wait queue. Per file: 32 MiB, 100,000 events, three-second copy/analysis horizon, 64 retained contention sites, four stack frames/site, eight output sites. Result JSON is at most 16 KiB; oversize serialization fails clearly. MCP frames max 8,192 characters, session max 128 frames or 512 KiB; restart after the intentional lifetime budget. Comparison analyzes two files and may use twice the per-file budget. Run with the documented heap cap. [Limitations](docs/limitations.md) cover trusted-file/parser and filesystem race assumptions.

## Testing and status

`./mvnw verify` runs real JFR/core tests and formatting. `./mvnw verify -Pintegration` also tests a separate packaged CLI, official SDK stdio round trip and an actual directory-link escape. It requires no Docker. Apply formatting with `spotless:apply`.

| Claim | Evidence | Command |
|---|---|---|
| Same file produces deterministic evidence, real contention stack present | TriageTest | `./mvnw test` |
| Bounds, invalid recording and missing-observation warnings | TriageTest | same |
| CLI/MCP share results and deny escapes | TriageIT | `./mvnw verify -Pintegration` |
| Actual filesystem link cannot escape root | TriageIT | same |

See [verification](docs/verification.md) for execution results and [ADR](docs/adr/001-jfr-evidence.md) for design choices. MIT source; JDK, MCP SDK and shaded dependencies retain their own licenses/notices. CI is prepared, not claimed passing on GitHub. No company recordings, published release, production SLA or causal AI diagnosis is included.

## Review corrections — 2026-09-20

The contention-only tool now includes truncated and eventsRead, preserving machine-readable evidence that the analysis hit a budget.
