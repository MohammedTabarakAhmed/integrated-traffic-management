package gov.itms.document.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-081")
class MediaTypesTest {

    private static Optional<String> sniff(int... bytes) {
        byte[] head = new byte[16];
        for (int i = 0; i < bytes.length; i++) {
            head[i] = (byte) bytes[i];
        }
        return MediaTypes.sniff(head, bytes.length);
    }

    private static Optional<String> sniff(String ascii) {
        byte[] b = ascii.getBytes(StandardCharsets.ISO_8859_1);
        byte[] head = new byte[16];
        System.arraycopy(b, 0, head, 0, Math.min(16, b.length));
        return MediaTypes.sniff(head, Math.min(16, b.length));
    }

    @Test
    @DisplayName("CC-081: the real type is recognised from the first bytes")
    void knownTypes() {
        assertThat(sniff("%PDF-1.7\n")).contains("application/pdf");
        assertThat(sniff(0xFF, 0xD8, 0xFF, 0xE0)).contains("image/jpeg");
        assertThat(sniff(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)).contains("image/png");
        assertThat(sniff("RIFF\0\0\0\0WEBPVP8 ")).contains("image/webp");
        assertThat(sniff(0x49, 0x49, 0x2A, 0x00)).contains("image/tiff");
        assertThat(sniff(0x4D, 0x4D, 0x00, 0x2A)).contains("image/tiff");
        assertThat(sniff("\0\0\0\u0018ftypisom")).contains("video/mp4");
        assertThat(sniff("\0\0\0\u0014ftypqt  ")).contains("video/quicktime");
        assertThat(sniff("\0\0\0\u0018ftypheic")).contains("image/heic");
    }

    @Test
    @DisplayName("CC-081: executables, scripts, truncated headers and empty files are not recognised")
    void unknownTypes() {
        assertThat(sniff("MZ\u0090\0\u0003\0\0\0")).isEmpty();
        assertThat(sniff("#!/bin/sh\nrm -rf")).isEmpty();
        assertThat(sniff("<html><body>")).isEmpty();
        assertThat(sniff("%PDF")).as("truncated magic").isEmpty();
        assertThat(sniff(0xFF, 0xD8)).isEmpty();
        assertThat(MediaTypes.sniff(new byte[16], 0)).isEmpty();
    }
}
