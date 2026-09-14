# References

- [JDK 21 RecordingFile](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jfr/jdk/jfr/consumer/RecordingFile.html): streaming reader and trusted-source requirement.
- [JDK 21 RecordedEvent](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.jfr/jdk/jfr/consumer/RecordedEvent.html): event timestamps, durations and stacks.
- [Official Java MCP SDK](https://java.sdk.modelcontextprotocol.io/latest/server/): stdio/tools.

Checked 2026-09-14. Java 21, MCP SDK 2.0.1, JUnit 6.1.3, Maven 3.9.16, Wrapper 3.3.4 and explicitly pinned stable Maven plugins. Boot 4.1.1 BOM manages compatible transitive libraries without any Boot runtime. SDK MIT and JUnit EPL-2.0; dependency notices are appended in the shaded artifact.
