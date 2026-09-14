package tr.com.akifsen.triage;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import jdk.jfr.consumer.*;

public final class Triage {
    private static final List<String> TYPES =
            List.of("jdk.GCPhasePause", "jdk.JavaMonitorEnter", "jdk.SocketRead", "jdk.SocketWrite");
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private final Path root;
    private final Semaphore admission = new Semaphore(2);

    public Triage(Path root) throws IOException {
        this.root = root.toRealPath();
        if (!Files.isDirectory(this.root)) throw new IOException("Root is not a directory");
    }

    public Object execute(String tool, Map<String, Object> args) throws Exception {
        if (!admission.tryAcquire()) throw new IllegalStateException("busy");
        try {
            if (args == null) throw new IllegalArgumentException("arguments_required");
            return switch (tool) {
                case "summarize_recording" -> {
                    exact(args, "file");
                    yield analyze(file(args, "file"));
                }
                case "top_contention_sites" -> {
                    exact(args, "file");
                    var report = analyze(file(args, "file"));
                    yield Map.of(
                            "sourceSha256",
                            report.get("sourceSha256"),
                            "observedInterval",
                            report.get("observedInterval"),
                            "sites",
                            report.get("contentionSites"),
                            "warnings",
                            report.get("warnings"));
                }
                case "compare_recordings" -> {
                    exact(args, "before", "after");
                    var a = analyze(file(args, "before"));
                    var b = analyze(file(args, "after"));
                    yield Map.of(
                            "before",
                            a,
                            "after",
                            b,
                            "warning",
                            "Compare evidence and observation lengths; differing settings, sampling and workloads can explain changes. This is not a causal regression verdict.");
                }
                default -> throw new IllegalArgumentException("unknown_tool");
            };
        } finally {
            admission.release();
        }
    }

    private static void exact(Map<String, Object> args, String... keys) {
        if (!args.keySet().equals(Set.of(keys))) throw new IllegalArgumentException("unexpected_arguments");
    }

    private Path file(Map<String, Object> args, String key) throws IOException {
        if (!(args.get(key) instanceof String name) || name.length() > 256 || name.isBlank())
            throw new IllegalArgumentException("invalid_file");
        Path relative = Path.of(name);
        if (relative.isAbsolute()) throw new IllegalArgumentException("relative_path_required");
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root) || !candidate.toString().endsWith(".jfr"))
            throw new IllegalArgumentException("path_denied");
        Path walk = root;
        for (Path part : root.relativize(candidate)) {
            walk = walk.resolve(part);
            if (Files.isSymbolicLink(walk)) throw new IllegalArgumentException("symlink_denied");
        }
        if (!candidate.toRealPath().startsWith(root)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                || Files.size(candidate) > MAX_BYTES) throw new IllegalArgumentException("file_denied_or_oversize");
        return candidate;
    }

    private Map<String, Object> analyze(Path input) throws Exception {
        Path snapshot = Files.createTempFile("jfr-triage-", ".jfr");
        var digest = MessageDigest.getInstance("SHA-256");
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        try {
            // Snapshot the authorized regular file with a hard byte bound; never parse a growing live recording.
            try (var in = Files.newInputStream(input, LinkOption.NOFOLLOW_LINKS);
                    var out = Files.newOutputStream(snapshot)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    total += n;
                    if (total > MAX_BYTES || System.nanoTime() > deadline) throw new IOException("copy_budget");
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
            Map<String, long[]> totals = new TreeMap<>();
            TYPES.forEach(type -> totals.put(type, new long[2]));
            Map<String, long[]> sites = new TreeMap<>();
            Set<String> present = new TreeSet<>();
            List<String> warnings = new ArrayList<>();
            Instant first = null, last = null;
            int events = 0;
            boolean truncated = false;
            try (var recording = new RecordingFile(snapshot)) {
                recording.readEventTypes().forEach(type -> {
                    if (TYPES.contains(type.getName())) present.add(type.getName());
                });
                while (recording.hasMoreEvents()) {
                    if (events == 100000 || System.nanoTime() > deadline) {
                        truncated = true;
                        warnings.add("event_or_time_budget_exhausted");
                        break;
                    }
                    RecordedEvent event = recording.readEvent();
                    events++;
                    if (first == null || event.getStartTime().isBefore(first)) first = event.getStartTime();
                    if (last == null || event.getEndTime().isAfter(last)) last = event.getEndTime();
                    String type = event.getEventType().getName();
                    long[] aggregate = totals.get(type);
                    if (aggregate == null) continue;
                    long nanos = event.getDuration().toNanos();
                    aggregate[0]++;
                    aggregate[1] = Math.addExact(aggregate[1], nanos);
                    if (type.equals("jdk.JavaMonitorEnter")) {
                        String site = stack(event);
                        long[] values = sites.get(site);
                        if (values == null) {
                            if (sites.size() == 64) {
                                truncated = true;
                                continue;
                            }
                            values = new long[2];
                            sites.put(site, values);
                        }
                        values[0]++;
                        values[1] += nanos;
                    }
                }
            }
            Map<String, Object> summary = new TreeMap<>();
            totals.forEach((type, value) -> summary.put(
                    type,
                    Map.of(
                            "schemaPresent",
                            present.contains(type),
                            "eventCount",
                            value[0],
                            "totalDurationNanos",
                            value[1])));
            List<Map<String, Object>> top = sites.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, long[]>>comparingLong(e -> e.getValue()[1])
                            .reversed()
                            .thenComparing(Map.Entry::getKey))
                    .limit(8)
                    .map(e -> Map.<String, Object>of(
                            "stack", e.getKey(), "count", e.getValue()[0], "totalDurationNanos", e.getValue()[1]))
                    .toList();
            if (truncated) warnings.add("Partial analysis; sites or event budget may omit evidence.");
            warnings.add(
                    "No observed event is not proof of absence. Recording settings/thresholds can hide events; socket wait is not necessarily a fault.");
            warnings.add(
                    "Summed overlapping event durations are not wall-clock CPU utilization. GCPhasePause excludes nested phase events.");
            return Map.of(
                    "sourceSha256",
                    HexFormat.of().formatHex(digest.digest()),
                    "observedInterval",
                    Map.of(
                            "start",
                            first == null ? "no_events" : first.toString(),
                            "end",
                            last == null ? "no_events" : last.toString()),
                    "eventsRead",
                    events,
                    "eventEvidence",
                    summary,
                    "contentionSites",
                    top,
                    "truncated",
                    truncated,
                    "warnings",
                    warnings);
        } finally {
            Files.deleteIfExists(snapshot);
        }
    }

    private static String stack(RecordedEvent event) {
        RecordedStackTrace trace = event.getStackTrace();
        if (trace == null) return "stack_not_recorded";
        StringBuilder result = new StringBuilder();
        for (RecordedFrame frame : trace.getFrames().stream().limit(4).toList()) {
            String symbol = frame.getMethod().getType().getName() + "."
                    + frame.getMethod().getName() + ":" + frame.getLineNumber();
            result.append(symbol.length() > 160 ? symbol.substring(0, 160) : symbol)
                    .append('\n');
        }
        return result.toString();
    }
}
