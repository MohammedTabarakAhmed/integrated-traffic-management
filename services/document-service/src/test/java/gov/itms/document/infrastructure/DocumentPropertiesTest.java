package gov.itms.document.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.document.domain.Document;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

@Tag("CC-081")
@Tag("NFR-042")
class DocumentPropertiesTest {

    private static final DocumentProperties.Store STORE = new DocumentProperties.Store(URI.create("http://minio:9000"),
            "itms-documents", "local", "key", "secret", Duration.ofSeconds(10));
    private static final DocumentProperties.Scanner SCANNER = new DocumentProperties.Scanner("clamav", 3310,
            Duration.ofSeconds(60));
    private static final DocumentProperties.Certificates CERTS = new DocumentProperties.Certificates("CRT",
            "https://verify.itms.example/v1/certificate-verifications", null);

    private static DocumentProperties.Type type(String code, List<String> media, DataSize max) {
        return new DocumentProperties.Type(code, media, max, Document.Classification.CONFIDENTIAL);
    }

    @Test
    @DisplayName("CC-081: allowed types and sizes per document type become the service's policies")
    void typesBecomePolicies() {
        DocumentProperties p = new DocumentProperties(STORE, SCANNER,
                List.of(type("ACCIDENT_VIDEO", List.of("video/mp4"), DataSize.ofGigabytes(2))), CERTS);
        assertThat(p.types().getFirst().toPolicy().maxBytes()).isEqualTo(DataSize.ofGigabytes(2).toBytes());
        assertThat(p.types().getFirst().toPolicy().mediaTypes()).containsExactly("video/mp4");
    }

    @Test
    @DisplayName("CC-081, NFR-042: unknown media types, sizes outside 1 KB-5 GB, duplicates and CERTIFICATE are refused")
    void typeRules() {
        assertThatThrownBy(() -> type("X_DOC", List.of("application/x-msdownload"), DataSize.ofMegabytes(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("media-types");
        assertThatThrownBy(() -> type("X_DOC", List.of("application/pdf"), DataSize.ofBytes(1023)))
                .hasMessageContaining("max-size");
        assertThatThrownBy(() -> type("X_DOC", List.of("application/pdf"), DataSize.ofGigabytes(6)))
                .hasMessageContaining("max-size");
        assertThatThrownBy(() -> type("x", List.of("application/pdf"), DataSize.ofMegabytes(1)))
                .hasMessageContaining("code");
        DocumentProperties.Type pdf = type("X_DOC", List.of("application/pdf"), DataSize.ofMegabytes(1));
        assertThatThrownBy(() -> new DocumentProperties(STORE, SCANNER, List.of(pdf, pdf), CERTS))
                .hasMessageContaining("twice");
        assertThatThrownBy(() -> new DocumentProperties(STORE, SCANNER,
                List.of(type("CERTIFICATE", List.of("application/pdf"), DataSize.ofMegabytes(1))), CERTS))
                .hasMessageContaining("CERTIFICATE");
        assertThatThrownBy(() -> new DocumentProperties(STORE, SCANNER, List.of(), CERTS))
                .hasMessageContaining("at least one");
    }

    @Test
    @Tag("SEC-004")
    @DisplayName("SEC-004: the store needs its keys (from secret files), a bucket name and a sane timeout")
    void storeAndScanner() {
        URI uri = URI.create("http://minio:9000");
        assertThatThrownBy(() -> new DocumentProperties.Store(uri, "itms-documents", "local", "", "s",
                Duration.ofSeconds(10))).hasMessageContaining("secret files");
        assertThatThrownBy(() -> new DocumentProperties.Store(uri, "Bad_Bucket", "local", "k", "s",
                Duration.ofSeconds(10))).hasMessageContaining("bucket");
        assertThatThrownBy(() -> new DocumentProperties.Store(uri, "itms-documents", "local", "k", "s",
                Duration.ofMinutes(11))).hasMessageContaining("timeout");
        assertThatThrownBy(() -> new DocumentProperties.Scanner("", 3310, Duration.ofSeconds(60)))
                .hasMessageContaining("host");
        assertThatThrownBy(() -> new DocumentProperties.Scanner("clamav", 70000, Duration.ofSeconds(60)))
                .hasMessageContaining("port");
        assertThatThrownBy(() -> new DocumentProperties.Certificates("C1", "https://x", null))
                .hasMessageContaining("number-prefix");
        assertThatThrownBy(() -> new DocumentProperties.Certificates("CRT", "http://verify.example", null))
                .hasMessageContaining("https");
    }
}
