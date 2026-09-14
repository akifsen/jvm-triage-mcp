package tr.com.akifsen.triage;

import static org.junit.jupiter.api.Assertions.*;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.*;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Timeout(30)
class TriageIT {
    @TempDir
    Path root;

    static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    static String jar() {
        return Path.of("target/jvm-triage-mcp-0.1.0-SNAPSHOT.jar")
                .toAbsolutePath()
                .toString();
    }

    @Test
    void realStdioSdkRoundTripAndPathDenial() throws Exception {
        JfrFixture.record(root.resolve("fixture.jfr"), true);
        var params = ServerParameters.builder(javaCommand())
                .args("-Xmx128m", "-jar", jar(), "--stdio")
                .env(Map.of("JFR_ROOT", root.toString()))
                .build();
        try (var client = McpClient.sync(new StdioClientTransport(params, McpJsonDefaults.getMapper(), 32768))
                .requestTimeout(Duration.ofSeconds(10))
                .build()) {
            client.initialize();
            assertEquals(3, client.listTools().tools().size());
            assertTrue(client.callTool(
                            new McpSchema.CallToolRequest("summarize_recording", Map.of("file", "../outside.jfr")))
                    .isError());
            var result = client.callTool(
                    new McpSchema.CallToolRequest("summarize_recording", Map.of("file", "fixture.jfr")));
            assertFalse(result.isError());
            String text = ((McpSchema.TextContent) result.content().getFirst()).text();
            assertEquals(
                    McpJsonDefaults.getMapper()
                            .readValue(
                                    Main.json(new Triage(root)
                                            .execute("summarize_recording", Map.of("file", "fixture.jfr"))),
                                    Map.class),
                    McpJsonDefaults.getMapper().readValue(text, Map.class));
        }
    }

    @Test
    void packagedCliAnalyzesFixture() throws Exception {
        JfrFixture.record(root.resolve("fixture.jfr"), true);
        var builder = new ProcessBuilder(javaCommand(), "-Xmx128m", "-jar", jar(), "--session");
        builder.environment().put("JFR_ROOT", root.toString());
        var process = builder.start();
        try {
            try (var writer = process.outputWriter()) {
                writer.write("{\"tool\":\"top_contention_sites\",\"arguments\":{\"file\":\"fixture.jfr\"}}\n");
            }
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            String out = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(out.contains("JfrFixture"));
            assertFalse(out.contains(root.toString()));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test
    void directoryLinkCannotEscapeAllowedRoot() throws Exception {
        Path allowed = Files.createDirectory(root.resolve("allowed"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        JfrFixture.record(outside.resolve("private.jfr"), true);
        Path link = allowed.resolve("link");
        if (System.getProperty("os.name").startsWith("Windows")) {
            Process process =
                    new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString()).start();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        } else Files.createSymbolicLink(link, outside);
        assertThrows(
                IllegalArgumentException.class,
                () -> new Triage(allowed).execute("summarize_recording", Map.of("file", "link/private.jfr")));
    }
}
