# ADR 001: Deterministic bounded evidence from trusted local recordings

Context: JFR events may be absent because of configuration, and duration totals across threads can overlap. An opaque diagnosis without recorded evidence is easy to overstate. Reading all events at once scales poorly.

Decision: use JDK RecordingFile streaming, fixed event names, exact counts/duration sums and bounded stack aggregates. Preserve missing-data/truncation warnings and observed interval. Share the core between CLI and the official MCP SDK. Snapshot authorized regular files within a byte/time budget; no live process access. Enforce finite per-frame/per-session input as well as operation admission.

Alternatives: loadAllEvents; invoke jcmd/jstack; unbounded filename access; heuristic prose-only diagnosis. These either expand authority or hide evidence/limits.

Cost/limits: only trusted recordings and operator-controlled roots; JDK parser calls are not forcibly preemptible. Caps can produce partial analyses and finite sessions require restarts. Comparison reports evidence rather than asserting a causal regression.
