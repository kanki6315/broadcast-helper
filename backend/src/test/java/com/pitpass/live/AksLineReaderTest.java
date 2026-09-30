package com.pitpass.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Line framing for both paths. The recording is the contract: whichever path
 * a line takes, the tee sees exactly the bytes the buffered reader would have
 * returned, once, in order.
 */
class AksLineReaderTest {

    /** Each line the tee saw, as text. */
    private final List<String> teed = new ArrayList<>();
    private final List<String> streamed = new ArrayList<>();

    private AksLineReader reader(InputStream in, int maxLine, long maxStreamed) {
        return new AksLineReader(in, maxLine, maxStreamed, epochMs -> new AksLineReader.Tee() {
            private final ByteArrayOutputStream line = new ByteArrayOutputStream();

            @Override
            public void write(byte[] bytes, int offset, int length) {
                line.write(bytes, offset, length);
            }

            @Override
            public void end() {
                teed.add(line.toString(StandardCharsets.UTF_8));
            }
        });
    }

    private static InputStream bytes(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Hands out one byte per read, so every boundary case (a CR at the end of a chunk) is hit. */
    private static InputStream trickle(String text) {
        return new FilterInputStream(bytes(text)) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(len, 1));
            }
        };
    }

    private AksLineReader.StreamHandler collect() {
        return (id, channel, data) -> streamed.add(new String(data.readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void bufferedLinesDropTheirLineEndingsAndBlankLines() throws Exception {
        AksLineReader reader = reader(bytes("LOGIN:1+::{}\r\n\r\n\nACK:2+::\nJOIN:3+:a.b:\r"), 1 << 10, 1 << 20);
        assertEquals("LOGIN:1+::{}", new String(reader.next(), StandardCharsets.UTF_8));
        assertEquals("ACK:2+::", new String(reader.next(), StandardCharsets.UTF_8));
        assertEquals("JOIN:3+:a.b:", new String(reader.next(), StandardCharsets.UTF_8));
        assertNull(reader.next());
        assertEquals(List.of("LOGIN:1+::{}", "ACK:2+::", "JOIN:3+:a.b:"), teed);
    }

    @Test
    void jsonFramesStreamAndAreTeedExactlyAsTheBufferedPathWouldReturnThem() throws Exception {
        String feed = "JSON:7::{\"a\":\"x\\ry\"}\r\n"   // an escaped CR is just text
                + "JSON: : : {\"b\":\"one\rtwo\"}\r\r\n" // a raw CR mid-line is kept, one before CRLF too
                + "ACK:8+::\r\n"
                + "JSON:9::{\"c\":1}";                  // no line ending at end of stream
        for (InputStream in : List.of(bytes(feed), trickle(feed))) {
            teed.clear();
            streamed.clear();
            AksLineReader buffered = reader(bytes(feed), 1 << 10, 1 << 20);
            List<String> expected = new ArrayList<>();
            byte[] line;
            while ((line = buffered.next()) != null) {
                expected.add(new String(line, StandardCharsets.UTF_8));
            }
            teed.clear();

            AksLineReader reader = reader(in, 1 << 10, 1 << 20);
            reader.streamJsonTo(collect());
            assertEquals("ACK:8+::", new String(reader.next(), StandardCharsets.UTF_8));
            assertNull(reader.next());

            assertEquals(expected, teed, "the recording is the same whichever path a line took");
            assertEquals(List.of("{\"a\":\"x\\ry\"}", " {\"b\":\"one\rtwo\"}\r", "{\"c\":1}"), streamed);
        }
    }

    @Test
    void whatTheHandlerLeavesUnreadIsDrainedAndStillRecorded() throws Exception {
        AksLineReader reader = reader(trickle("JSON:1::{\"big\":[1,2,3,4,5]}\r\nACK:2+::\r\n"), 1 << 10, 1 << 20);
        reader.streamJsonTo((id, channel, data) -> data.read()); // one byte, then walks away
        assertEquals("ACK:2+::", new String(reader.next(), StandardCharsets.UTF_8));
        assertEquals(List.of("JSON:1::{\"big\":[1,2,3,4,5]}", "ACK:2+::"), teed);
    }

    @Test
    void anUnparseableFrameIsReportedAfterBeingReadToItsEnd() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AksLineReader reader = reader(bytes("JSON:1::{\"broken\":\r\nACK:2+::\r\n"), 1 << 10, 1 << 20);
        reader.streamJsonTo((id, channel, data) -> mapper.readTree(data));
        assertThrows(AksLineReader.UnreadableFrame.class, reader::next);
        assertEquals("ACK:2+::", new String(reader.next(), StandardCharsets.UTF_8));
        assertEquals(List.of("JSON:1::{\"broken\":", "ACK:2+::"), teed);
    }

    @Test
    void theHeaderIsPassedAlong() throws Exception {
        List<String> seen = new ArrayList<>();
        AksLineReader reader = reader(bytes("JSON:12+:timing.analysis.laps:{}\n"), 1 << 10, 1 << 20);
        reader.streamJsonTo((id, channel, data) -> seen.add(id + "|" + channel));
        assertNull(reader.next());
        assertEquals(List.of("12+|timing.analysis.laps"), seen);
    }

    @Test
    void streamedLinesAreCappedByTheirOwnLimitAndBufferedOnesByTheirs() throws Exception {
        String big = "JSON:1::{\"x\":\"" + "a".repeat(5_000) + "\"}\n";

        AksLineReader streaming = reader(bytes(big), 1 << 10, 1 << 20);
        streaming.streamJsonTo(collect());
        assertNull(streaming.next(), "far over max-line-bytes, and fine: it was never buffered");
        assertEquals(1, streamed.size());

        AksLineReader capped = reader(bytes(big), 1 << 10, 1_000);
        capped.streamJsonTo(collect());
        IOException e = assertThrows(IOException.class, capped::next);
        assertTrue(e.getMessage().contains("Streamed line exceeds"), e.getMessage());

        AksLineReader buffered = reader(bytes(big), 1 << 10, 1 << 20);
        e = assertThrows(IOException.class, buffered::next);
        assertTrue(e.getMessage().contains("Line exceeds"), e.getMessage());
    }
}
