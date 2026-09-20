package tr.com.akifsen.triage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
class TriageTest {
    @TempDir
    Path root;

    @Test
    void deterministicRealJfrEvidence() throws Exception {
        JfrFixture.record(root.resolve("contention.jfr"), true);
        var triage = new Triage(root);
        Object first = triage.execute("summarize_recording", Map.of("file", "contention.jfr"));
        Object second = triage.execute("summarize_recording", Map.of("file", "contention.jfr"));
        assertEquals(first, second);
        var evidence = (Map<?, ?>) ((Map<?, ?>) first).get("eventEvidence");
        assertTrue(((Number) ((Map<?, ?>) evidence.get("jdk.JavaMonitorEnter")).get("eventCount")).longValue() > 0);
        assertTrue(Main.json(first).contains("JfrFixture"));
    }

    @Test
    void compareDoesNotClaimCausality() throws Exception {
        JfrFixture.record(root.resolve("a.jfr"), true);
        JfrFixture.record(root.resolve("b.jfr"), false);
        String report =
                Main.json(new Triage(root).execute("compare_recordings", Map.of("before", "a.jfr", "after", "b.jfr")));
        assertTrue(report.contains("not a causal"));
        assertTrue(report.contains("No observed event is not proof"));
    }

    @Test
    void deniesTraversalAbsoluteAndOversizeFiles() throws Exception {
        var triage = new Triage(root);
        assertThrows(
                IllegalArgumentException.class,
                () -> triage.execute("summarize_recording", Map.of("file", "../private.jfr")));
        assertThrows(
                IllegalArgumentException.class,
                () -> triage.execute(
                        "summarize_recording",
                        Map.of("file", root.resolve("private.jfr").toString())));
        try (var file = new java.io.RandomAccessFile(root.resolve("large.jfr").toFile(), "rw")) {
            file.setLength(32L * 1024 * 1024 + 1);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> triage.execute("summarize_recording", Map.of("file", "large.jfr")));
    }

    @Test
    void invalidRecordingIsNotHealthy() throws Exception {
        Files.writeString(root.resolve("bad.jfr"), "not a recording");
        assertThrows(
                java.io.IOException.class,
                () -> new Triage(root).execute("summarize_recording", Map.of("file", "bad.jfr")));
    }

    @Test
    void emptyRecordingReportsMissingObservations() throws Exception {
        try (var recording = new jdk.jfr.Recording()) {
            recording.start();
            recording.stop();
            recording.dump(root.resolve("empty.jfr"));
        }
        String report = Main.json(new Triage(root).execute("summarize_recording", Map.of("file", "empty.jfr")));
        assertTrue(report.contains("No observed event is not proof"));
    }

    @jdk.jfr.Name("triage.test.Noise")
    static class Noise extends jdk.jfr.Event {}

    @Test
    void contentionProjectionPreservesPartialAnalysisStatus() throws Exception {
        try (var recording = new jdk.jfr.Recording()) {
            recording.enable(Noise.class).withoutStackTrace();
            recording.start();
            for (int i = 0; i < 100001; i++) new Noise().commit();
            recording.stop();
            recording.dump(root.resolve("bounded.jfr"));
        }
        var report = (Map<?, ?>) new Triage(root).execute("top_contention_sites", Map.of("file", "bounded.jfr"));
        assertEquals(true, report.get("truncated"));
        assertTrue(((Number) report.get("eventsRead")).intValue() <= 100000);
    }
}
