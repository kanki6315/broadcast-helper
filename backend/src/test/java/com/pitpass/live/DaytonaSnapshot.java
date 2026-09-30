package com.pitpass.live;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * A synthetic reconnect snapshot of timing.analysis.laps the size of a
 * Rolex 24: one line, generated as it is read so the test never holds it
 * either. 60 cars × 650 laps = 39,000 laps, each with 20 loop sectors, comes
 * to ~100 MB — above the 45–78 MB estimated for the real thing, and far over
 * the 32 MB max-line-bytes that buffered lines are held to.
 */
final class DaytonaSnapshot extends InputStream {

    static final int CARS = 60;
    static final int LAPS_PER_CAR = 650;
    static final int LOOP_SECTORS = 20;
    static final int LAPS = CARS * LAPS_PER_CAR;

    private final CRC32 crc = new CRC32();
    private byte[] chunk;
    private int at;
    private int car;
    private int lap = 1;
    private int stage; // 0 header, 1 laps, 2 footer + CRLF, 3 done
    private long length;

    /** CRC of the line as the recording should hold it: no CRLF. */
    long crc() {
        return crc.getValue();
    }

    /** Bytes of the line, CRLF excluded. */
    long length() {
        return length;
    }

    @Override
    public int read() {
        byte[] one = new byte[1];
        return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) {
        while (chunk == null || at == chunk.length) {
            if (!next()) {
                return -1;
            }
        }
        int n = Math.min(len, chunk.length - at);
        System.arraycopy(chunk, at, b, off, n);
        at += n;
        return n;
    }

    private boolean next() {
        String text;
        switch (stage) {
            case 0 -> {
                text = "JSON:::{\"timing\":{\"analysis\":{\"laps\":{";
                stage = 1;
            }
            case 1 -> {
                StringBuilder s = new StringBuilder();
                if (lap == 1) {
                    s.append(car == 0 ? "" : "}},").append("\"").append(car + 1).append("\":{\"laps\":{");
                } else {
                    s.append(',');
                }
                s.append('"').append(lap).append("\":")
                        .append(AnalysisFixtures.lap(lap, 1 + lap / 100, 95_000 + (lap * 37 + car * 11) % 4_000, LOOP_SECTORS));
                if (++lap > LAPS_PER_CAR) {
                    lap = 1;
                    if (++car == CARS) {
                        stage = 2;
                    }
                }
                text = s.toString();
            }
            case 2 -> {
                text = "}}}}}}";
                stage = 3;
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                crc.update(bytes);
                length += bytes.length;
                chunk = (text + "\r\n").getBytes(StandardCharsets.UTF_8);
                at = 0;
                return true;
            }
            default -> {
                return false;
            }
        }
        chunk = text.getBytes(StandardCharsets.UTF_8);
        crc.update(chunk);
        length += chunk.length;
        at = 0;
        return true;
    }
}
