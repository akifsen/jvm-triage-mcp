package tr.com.akifsen.triage;

import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import jdk.jfr.Recording;

public final class JfrFixture {
    public static void record(Path destination, boolean contended) throws Exception {
        Files.createDirectories(destination.toAbsolutePath().getParent());
        try (var recording = new Recording()) {
            for (String event :
                    new String[] {"jdk.GCPhasePause", "jdk.JavaMonitorEnter", "jdk.SocketRead", "jdk.SocketWrite"})
                recording.enable(event).withThreshold(Duration.ZERO).withStackTrace();
            recording.start();
            Object lock = new Object();
            CountDownLatch held = new CountDownLatch(1),
                    release = new CountDownLatch(1),
                    finished = new CountDownLatch(1);
            Thread owner = Thread.ofPlatform().start(() -> {
                synchronized (lock) {
                    held.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            if (!held.await(1, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture owner failed");
            Thread waiter = Thread.ofPlatform().start(() -> {
                synchronized (contended ? lock : new Object()) {
                    finished.countDown();
                }
            });
            try {
                long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
                if (contended) {
                    while (waiter.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
                        Thread.onSpinWait();
                    if (waiter.getState() != Thread.State.BLOCKED)
                        throw new IllegalStateException("Contention not observed");
                } else if (!finished.await(1, TimeUnit.SECONDS))
                    throw new IllegalStateException("Independent lock did not complete");
            } finally {
                release.countDown();
            }
            owner.join(2000);
            waiter.join(2000);
            if (owner.isAlive() || waiter.isAlive()) throw new IllegalStateException("Fixture did not terminate");
            recording.stop();
            recording.dump(destination);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: JfrFixture output-directory");
        Path root = Path.of(args[0]);
        record(root.resolve("contended.jfr"), true);
        record(root.resolve("independent.jfr"), false);
        System.out.println("Generated synthetic contended.jfr and independent.jfr");
    }
}
