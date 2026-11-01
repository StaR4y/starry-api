package tech.starryapi.platform.xproto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.starryapi.platform.xproto.IncrementalSseDecoder.Event;
import tech.starryapi.platform.xproto.IncrementalSseDecoder.SseDecodingException;

class IncrementalSseDecoderTest {

    private static Event data(String data) {
        return new Event(false, null, data);
    }

    private static List<Event> decode(byte[] bytes, int pieceSize) {
        IncrementalSseDecoder decoder = new IncrementalSseDecoder();
        List<Event> events = new ArrayList<>();
        for (int offset = 0; offset < bytes.length; offset += pieceSize) {
            events.addAll(decoder.feed(bytes, offset, Math.min(pieceSize, bytes.length - offset)));
        }
        events.addAll(decoder.finish());
        return events;
    }

    private static List<Event> decode(String text) {
        return decode(text.getBytes(StandardCharsets.UTF_8), Integer.MAX_VALUE);
    }

    @Test
    void anEventIsDeliveredWhenItsBlankLineArrives() {
        IncrementalSseDecoder decoder = new IncrementalSseDecoder();

        assertThat(decoder.feed("data: {\"a\":1}\n".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(decoder.feed("\n".getBytes(StandardCharsets.UTF_8))).containsExactly(data("{\"a\":1}"));
        assertThat(decoder.finish()).isEmpty();
    }

    @Test
    void theSameEventsComeOutWhereverThePiecesAreCut() {
        String body = "data: {\"text\":\"h\u00e9llo \u4f60\u597d \ud83d\ude00\"}\r\n\r\n"
            + ": keep-alive\n\n"
            + "event: error\ndata: first\ndata: second\n\n"
            + "data:[DONE]\r\r"
            + "data: tail\r\n\r\n";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        List<Event> expected = List.of(
            data("{\"text\":\"h\u00e9llo \u4f60\u597d \ud83d\ude00\"}"),
            new Event(true, null, "keep-alive"),
            new Event(false, "error", "first\nsecond"),
            data("[DONE]"),
            data("tail"));

        assertThat(decode(bytes, Integer.MAX_VALUE)).isEqualTo(expected);
        for (int piece = 1; piece <= 7; piece++) {
            assertThat(decode(bytes, piece)).as("pieces of %d bytes", piece).isEqualTo(expected);
        }
    }

    @Test
    void aCharacterCutInTheMiddleIsFinishedByTheNextPiece() {
        byte[] bytes = "data: \ud83d\ude00\n\n".getBytes(StandardCharsets.UTF_8);
        for (int cut = 1; cut < bytes.length; cut++) {
            IncrementalSseDecoder decoder = new IncrementalSseDecoder();
            List<Event> events = new ArrayList<>(decoder.feed(bytes, 0, cut));
            events.addAll(decoder.feed(bytes, cut, bytes.length - cut));

            assertThat(events).as("cut after %d bytes", cut).containsExactly(data("\ud83d\ude00"));
        }
    }

    @Test
    void lineEndsAreLfCrOrCrlfAndACrlfCutBetweenItsBytesIsOneEnd() {
        assertThat(decode("data: a\n\ndata: b\r\rdata: c\r\n\r\n")).containsExactly(data("a"), data("b"), data("c"));

        IncrementalSseDecoder decoder = new IncrementalSseDecoder();
        List<Event> events = new ArrayList<>();
        events.addAll(decoder.feed("data: a\r".getBytes(StandardCharsets.UTF_8)));
        events.addAll(decoder.feed("\n\r".getBytes(StandardCharsets.UTF_8)));
        events.addAll(decoder.feed("\ndata: b\n\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(events).containsExactly(data("a"), data("b"));
    }

    @Test
    void onlyOneSpaceAfterTheColonIsDropped() {
        assertThat(decode("data:x\n\ndata: x\n\ndata:  x\n\n")).containsExactly(data("x"), data("x"), data(" x"));
    }

    @Test
    void aDataLineWithoutValueIsAnEmptyEventAndABlankLineAloneIsNothing() {
        assertThat(decode("data\n\ndata:\n\n\n\n")).containsExactly(data(""), data(""));
    }

    @Test
    void commentsAreEventsOfTheirOwnAndDoNotEndOrJoinTheEventAround() {
        assertThat(decode("data: a\n: ping\ndata: b\n\n:\n\n")).containsExactly(
            new Event(true, null, "ping"), data("a\nb"), new Event(true, null, ""));
    }

    @Test
    void idRetryAndUnknownFieldsAreNotReadAndTheEventNameDoesNotLeakIntoTheNextEvent() {
        assertThat(decode("id: 7\nretry: 100\nunknown: x\nevent: first\ndata: a\n\ndata: b\n\nevent: lost\n\n"))
            .containsExactly(new Event(false, "first", "a"), data("b"));
    }

    @Test
    void aByteOrderMarkAtTheStartIsDropped() {
        assertThat(decode("\uFEFFdata: a\n\n")).containsExactly(data("a"));
    }

    @Test
    void whatIsLeftWhenTheBodyEndsIsDeliveredEvenWithoutABlankLine() {
        assertThat(decode("data: a\n\ndata: b")).containsExactly(data("a"), data("b"));
        assertThat(decode("data: a\n")).containsExactly(data("a"));
        assertThat(decode("data: partial")).containsExactly(data("partial"));
    }

    @Test
    void textThatHasNoDataLinesGivesNoEvents() {
        assertThat(decode("<html><body>Bad gateway</body></html>\n")).isEmpty();
        assertThat(decode("")).isEmpty();
    }

    @Test
    void bytesThatAreNotUtf8AreRefused() {
        IncrementalSseDecoder decoder = new IncrementalSseDecoder();
        byte[] bad = {'d', 'a', 't', 'a', ':', ' ', (byte) 0xC3, (byte) 0x28, '\n', '\n'};

        assertThatThrownBy(() -> decoder.feed(bad)).isInstanceOfSatisfying(SseDecodingException.class,
            error -> assertThat(error.isTooLarge()).isFalse());
    }

    /** Where the network cuts the pieces is no fact about the relay: what came before the bad bytes in a piece is theirs to read. */
    @Test
    void theEventsAPieceCompletedBeforeItsBadBytesComeWithTheFailure() {
        IncrementalSseDecoder decoder = new IncrementalSseDecoder();
        byte[] head = "data: {\"a\":1}\n\n: ping\n\ndata: second\n\ndata: ".getBytes(StandardCharsets.UTF_8);
        byte[] piece = new byte[head.length + 4];
        System.arraycopy(head, 0, piece, 0, head.length);
        piece[head.length] = (byte) 0xC3;
        piece[head.length + 1] = (byte) 0x28;
        piece[head.length + 2] = '\n';
        piece[head.length + 3] = '\n';

        assertThatThrownBy(() -> decoder.feed(piece)).isInstanceOfSatisfying(SseDecodingException.class, error -> {
            assertThat(error.isTooLarge()).isFalse();
            assertThat(error.events()).containsExactly(data("{\"a\":1}"), new Event(true, null, "ping"), data("second"));
        });
    }

    @Test
    void theSameBytesCutBeforeTheBadOnesGiveTheSameEventsAsAFailureOfTheNextPiece() {
        byte[] first = "data: one\n\n".getBytes(StandardCharsets.UTF_8);
        byte[] bad = {'d', 'a', 't', 'a', ':', ' ', (byte) 0xC3, (byte) 0x28, '\n', '\n'};
        IncrementalSseDecoder decoder = new IncrementalSseDecoder();

        assertThat(decoder.feed(first)).containsExactly(data("one"));
        assertThatThrownBy(() -> decoder.feed(bad)).isInstanceOfSatisfying(SseDecodingException.class,
            error -> assertThat(error.events()).isEmpty());
    }

    @Test
    void aBodyThatEndsInsideACharacterIsRefusedWhenItEnds() {
        IncrementalSseDecoder decoder = new IncrementalSseDecoder();
        byte[] cut = "data: \u4f60".getBytes(StandardCharsets.UTF_8);

        assertThat(decoder.feed(cut, 0, cut.length - 1)).isEmpty();
        assertThatThrownBy(decoder::finish).isInstanceOfSatisfying(SseDecodingException.class,
            error -> assertThat(error.isTooLarge()).isFalse());
    }

    @Test
    void moreBytesThanTheLimitAreRefusedAsTooLarge() {
        IncrementalSseDecoder decoder = new IncrementalSseDecoder(16);

        assertThat(decoder.feed("data: 1\n\n".getBytes(StandardCharsets.UTF_8))).hasSize(1);
        assertThatThrownBy(() -> decoder.feed("data: 22222\n\n".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOfSatisfying(SseDecodingException.class, error -> assertThat(error.isTooLarge()).isTrue());
        assertThat(decoder.bytesRead()).isEqualTo(9 + 13);
    }

    @Test
    void aByteBufferIsReadLikeBytes() {
        IncrementalSseDecoder decoder = new IncrementalSseDecoder();

        assertThat(decoder.feed(ByteBuffer.wrap("data: a\n\n".getBytes(StandardCharsets.UTF_8)))).containsExactly(data("a"));
    }

    @Test
    void nothingIsTakenAfterTheBodyHasEnded() {
        IncrementalSseDecoder decoder = new IncrementalSseDecoder();
        decoder.finish();

        assertThat(decoder.finish()).isEmpty();
        assertThatThrownBy(() -> decoder.feed(new byte[] {'x'})).isInstanceOf(IllegalStateException.class);
    }
}
