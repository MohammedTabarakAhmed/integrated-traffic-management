package gov.itms.document.domain;

import java.util.Optional;

/**
 * Recognises a file's real type from its first bytes (CC-081), so a renamed executable is not accepted as a PDF. Only
 * the types the BRD needs are known; anything else is refused.
 */
public final class MediaTypes {

    private MediaTypes() {
    }

    public static Optional<String> sniff(byte[] head, int length) {
        if (starts(head, length, 0x25, 0x50, 0x44, 0x46, 0x2D)) {
            return Optional.of("application/pdf");
        }
        if (starts(head, length, 0xFF, 0xD8, 0xFF)) {
            return Optional.of("image/jpeg");
        }
        if (starts(head, length, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of("image/png");
        }
        if (length >= 12 && starts(head, length, 0x52, 0x49, 0x46, 0x46) && head[8] == 'W' && head[9] == 'E'
                && head[10] == 'B' && head[11] == 'P') {
            return Optional.of("image/webp");
        }
        if (starts(head, length, 0x49, 0x49, 0x2A, 0x00) || starts(head, length, 0x4D, 0x4D, 0x00, 0x2A)) {
            return Optional.of("image/tiff");
        }
        if (length >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            String brand = new String(head, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (brand.startsWith("qt")) {
                return Optional.of("video/quicktime");
            }
            if (brand.startsWith("hei") || brand.startsWith("mif1")) {
                return Optional.of("image/heic");
            }
            return Optional.of("video/mp4");
        }
        return Optional.empty();
    }

    private static boolean starts(byte[] head, int length, int... magic) {
        if (length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((head[i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }
}
