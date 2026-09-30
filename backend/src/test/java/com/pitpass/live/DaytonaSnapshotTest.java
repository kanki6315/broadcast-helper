package com.pitpass.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The heap guard: a Rolex-24-size reconnect snapshot of timing.analysis, as
 * the single line the feed sends it, must stream through without being held
 * — and the recording must still get every byte.
 */
class DaytonaSnapshotTest {

    @TempDir
    Path tmp;

    @Test
    void aDaytonaSizeSnapshotLineStreamsThroughAndIsTeedByteForByte() throws Exception {
        AksStateTree tree = new AksStateTree();
        AtomicLong laps = new AtomicLong();
        AtomicLong loopSectorsStored = new AtomicLong();
        AnalysisRouter router = new AnalysisRouter(new ObjectMapper(), tree, op -> {
            if (op instanceof AnalysisRows.LapPatch l) {
                laps.incrementAndGet();
                if (l.sectors != null && l.sectors.size() != 3) {
                    loopSectorsStored.incrementAndGet();
                }
            }
            return true; // retained by nobody: this test is about throughput and framing
        }, new LiveCarSummaries());
        router.frame("", "", new java.io.ByteArrayInputStream(
                AnalysisFixtures.info(AnalysisFixtures.SESSION, "Race").getBytes()));

        DaytonaSnapshot line = new DaytonaSnapshot();
        CRC32 teed = new CRC32();
        AtomicLong teedBytes = new AtomicLong();
        AksLineReader reader = new AksLineReader(line, 32 * 1024 * 1024, 512L * 1024 * 1024,
                epochMs -> new AksLineReader.Tee() {
                    @Override
                    public void write(byte[] bytes, int offset, int length) {
                        teed.update(bytes, offset, length);
                        teedBytes.addAndGet(length);
                    }

                    @Override
                    public void end() {
                    }
                });
        reader.streamJsonTo(router);
        assertEquals(null, reader.next());

        assertEquals(DaytonaSnapshot.LAPS, laps.get());
        assertEquals(0, loopSectorsStored.get(), "loop sectors are skipped, never mistaken for sectors");
        assertTrue(line.length() > 32L * 1024 * 1024, "bigger than any buffered line may be: " + line.length());
        assertEquals(line.length(), teedBytes.get());
        assertEquals(line.crc(), teed.getValue(), "the recording gets the line byte for byte");
        assertEquals(null, tree.copyOf("timing.analysis"));
    }

    /**
     * The same snapshot in a JVM with production's -Xmx256m and SerialGC,
     * into an undrained queue of the writer's capacity. Measured numbers are
     * recorded in docs/LIVE_TIMING.md; the bound here leaves headroom for
     * the rest of the application.
     */
    @Test
    void itFitsProductionsHeapEvenWithTheDatabaseStalled() throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        Process process = new ProcessBuilder(java, "-Xmx256m", "-XX:+UseSerialGC", "-Xss512k",
                "-cp", System.getProperty("java.class.path"), DaytonaHeapProbe.class.getName())
                .redirectErrorStream(true)
                .start();
        Map<String, Long> out = new HashMap<>();
        StringBuilder log = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String s;
            while ((s = r.readLine()) != null) {
                log.append(s).append('\n');
                int eq = s.indexOf('=');
                if (eq > 0 && s.substring(0, eq).matches("[A-Z_]+")) {
                    out.put(s.substring(0, eq), Long.parseLong(s.substring(eq + 1).trim()));
                }
            }
        }
        assertTrue(process.waitFor(120, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), log.toString());
        System.out.println("Daytona heap probe (-Xmx256m): " + out);

        assertEquals(DaytonaSnapshot.LAPS, out.get("LAPS"));
        assertEquals(0L, out.get("DROPPED"), "the writer's queue holds a whole Daytona snapshot");
        assertEquals((long) DaytonaSnapshot.CARS, out.get("CARS"));
        for (String key : List.of("RETAINED_BYTES", "PEAK_OLD_GEN_BYTES")) {
            assertTrue(out.get(key) < 160L * 1024 * 1024, key + " " + out.get(key));
        }
    }
}
