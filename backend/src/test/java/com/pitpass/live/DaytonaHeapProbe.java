package com.pitpass.live;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Run in its own JVM by {@link DaytonaSnapshotTest} with production's heap
 * flags (-Xmx256m, SerialGC): streams the Daytona-size snapshot through the
 * real line reader, router and recorder into a queue the size of the
 * writer's, which nothing drains — the worst case, a database that has
 * stalled. Prints what stayed on the heap.
 */
public final class DaytonaHeapProbe {

    public static void main(String[] args) throws Exception {
        long before = usedAfterGc();
        ArrayBlockingQueue<AnalysisRows.Op> queue = new ArrayBlockingQueue<>(AnalysisWriter.DEFAULT_CAPACITY);
        AksStateTree tree = new AksStateTree();
        tree.merge((com.fasterxml.jackson.databind.node.ObjectNode) new ObjectMapper().readTree(
                AnalysisFixtures.info(AnalysisFixtures.SESSION, "Race")));
        LiveCarSummaries summaries = new LiveCarSummaries();
        AnalysisRouter router = new AnalysisRouter(new ObjectMapper(), tree, queue::offer, summaries, () -> null);
        // the router learns the session from a session frame, as on the wire
        router.frame("", "", new java.io.ByteArrayInputStream(
                AnalysisFixtures.info(AnalysisFixtures.SESSION, "Race").getBytes()));

        Path dir = Files.createTempDirectory("daytona-probe");
        LiveRecorder recorder = new LiveRecorder(dir, Duration.ofHours(1), (segment, key) -> { });
        DaytonaSnapshot line = new DaytonaSnapshot();
        AksLineReader reader = new AksLineReader(line, 32 * 1024 * 1024, 512L * 1024 * 1024, recorder::line);
        reader.streamJsonTo(router);
        long started = System.nanoTime();
        if (reader.next() != null) {
            throw new IllegalStateException("the snapshot should have been streamed, not returned");
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        recorder.close();

        long after = usedAfterGc();
        System.out.println("LINE_BYTES=" + line.length());
        System.out.println("LAPS=" + router.laps());
        System.out.println("QUEUED=" + queue.size());
        System.out.println("DROPPED=" + router.dropped());
        System.out.println("RETAINED_BYTES=" + (after - before));
        System.out.println("PEAK_OLD_GEN_BYTES=" + peakOldGen());
        System.out.println("MILLIS=" + millis);
        System.out.println("CARS=" + summaries.snapshot().size());
        if (queue.isEmpty()) {
            throw new IllegalStateException("keep the queue reachable to here");
        }
    }

    private static long usedAfterGc() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(50);
        }
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    private static long peakOldGen() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getType() == MemoryType.HEAP && p.getName().toLowerCase().contains("tenured"))
                .mapToLong(p -> p.getPeakUsage().getUsed())
                .max().orElse(-1);
    }
}
