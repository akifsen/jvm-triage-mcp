package tr.com.akifsen.triage;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public final class Main {
    private static final Map<String, List<String>> TOOLS = Map.of(
            "summarize_recording",
            List.of("file"),
            "top_contention_sites",
            List.of("file"),
            "compare_recordings",
            List.of("before", "after"));

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            System.err.println("JFR triage failed: invalid or denied recording, or exhausted budget.");
            System.exit(2);
        }
    }

    static void run(String[] args) throws Exception {
        var inspector = new Triage(java.nio.file.Path.of(System.getenv().getOrDefault("JFR_ROOT", "recordings")));
        if (args.length == 1 && args[0].equals("--stdio")) {
            CountDownLatch eof = new CountDownLatch(1);
            var input = new SessionInput(System.in, eof);
            var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper(), input, System.out, 8192);
            var builder = McpServer.sync(transport)
                    .serverInfo("jvm-triage", "0.1.0")
                    .requestTimeout(Duration.ofSeconds(3))
                    .immediateExecution(true)
                    .capabilities(
                            McpSchema.ServerCapabilities.builder().tools(false).build());
            TOOLS.forEach((name, fields) -> {
                Map<String, Object> properties = new LinkedHashMap<>();
                fields.forEach(
                        field -> properties.put(field, Map.of("type", "string", "maxLength", 128, "minLength", 1)));
                var tool = McpSchema.Tool.builder()
                        .name(name)
                        .description(
                                "Bounded local JFR evidence. File paths are relative to configured JFR_ROOT; no live process access.")
                        .inputSchema(Map.of(
                                "type",
                                "object",
                                "properties",
                                properties,
                                "required",
                                fields,
                                "additionalProperties",
                                false))
                        .build();
                builder.tools(new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
                    try {
                        return McpSchema.CallToolResult.builder()
                                .addTextContent(json(inspector.execute(name, request.arguments())))
                                .build();
                    } catch (Exception e) {
                        return McpSchema.CallToolResult.builder()
                                .isError(true)
                                .addTextContent(
                                        "Recording denied, invalid, or over budget. No live process was accessed.")
                                .build();
                    }
                }));
            });
            var server = builder.build();
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            eof.await();
            server.close();
        } else if (args.length == 1 && args[0].equals("--session")) {
            // Same bounded line reader as MCP. JSON envelopes are a CLI convenience, not a second MCP implementation.
            try (var reader = new BufferedReader(new InputStreamReader(
                    new SessionInput(System.in, new CountDownLatch(1)), StandardCharsets.UTF_8))) {
                String line;
                while ((line = readLine(reader)) != null) {
                    var request = McpJsonDefaults.getMapper().readValue(line, Map.class);
                    if (!(request.get("tool") instanceof String tool)
                            || !(request.get("arguments") instanceof Map arguments))
                        throw new IllegalArgumentException();
                    System.out.println(json(inspector.execute(tool, arguments)));
                }
            }
        } else if (args.length == 2 && args[1].length() <= 8192) {
            System.out.println(
                    json(inspector.execute(args[0], McpJsonDefaults.getMapper().readValue(args[1], Map.class))));
        } else throw new IllegalArgumentException("Usage: --stdio | --session | tool JSON-arguments");
    }

    private static String readLine(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = reader.read()) >= 0 && c != '\n') {
            if (line.length() == 8192) throw new IOException("line_limit");
            line.append((char) c);
        }
        return c < 0 && line.isEmpty() ? null : line.toString();
    }

    static String json(Object result) throws IOException {
        String json = McpJsonDefaults.getMapper().writeValueAsString(result);
        if (json.getBytes(StandardCharsets.UTF_8).length > 16384) throw new IOException("output_limit");
        return json;
    }

    static final class SessionInput extends FilterInputStream {
        private int bytes, lines;
        private final CountDownLatch eof;

        SessionInput(InputStream input, CountDownLatch eof) {
            super(input);
            this.eof = eof;
        }

        @Override
        public int read() throws IOException {
            if (bytes == 524288 || lines == 128) {
                eof.countDown();
                throw new IOException("session_budget");
            }
            int value = in.read();
            if (value < 0) eof.countDown();
            else {
                bytes++;
                if (value == '\n') lines++;
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            int first = read();
            if (first < 0) return -1;
            b[off] = (byte) first;
            // Do not wait for another byte after a complete newline-delimited frame.
            int count = 1;
            while (count < len && first != '\n' && in.available() > 0) {
                first = read();
                if (first < 0) break;
                b[off + count++] = (byte) first;
            }
            return count;
        }
    }
}
