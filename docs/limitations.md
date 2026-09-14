# Limitations

Use trusted local JFR files, as required by the JDK consumer API. A size cap does not harden the JDK parser against a deliberately malicious recording. `-Xmx128m` bounds the process heap; JVM native memory is separate. Budget checks occur between JFR event reads; one JDK parse call, filesystem stall or GC pause may exceed the nominal deadline.

The recording directory must be controlled by the local operator. Canonical containment, link checks, NOFOLLOW final-component access and bounded snapshotting reject ordinary escapes; they are not a guarantee against an adversary concurrently replacing directory ancestry. No arbitrary path outside the configured root is an intended capability. The private snapshot prevents growth/ordinary live-file changes during parsing; source mutation during the copy is not a supported input mode.

Only GCPhasePause, JavaMonitorEnter, SocketRead and SocketWrite are summarized. Other events contribute to the observed interval but are not analyzed; allocation profiling, leaks, CPU flame graphs and live process control are out of scope. Schema presence is not evidence that an event was enabled or occurred. Missing events/stacks and truncated reports must remain visible.

Top sites are the largest durations among at most the first 64 distinct observed sites; later unseen sites are omitted with `truncated=true`, not a globally exact heavy-hitter guarantee. Class/method names can themselves be sensitive; only use recordings you are authorized to disclose to the local MCP client.

Finite MCP session input prevents indefinite growth through the SDK's internal queues; clients must restart after the documented lifetime cap. No general MCP conformance certification or hosted CI result is claimed.
