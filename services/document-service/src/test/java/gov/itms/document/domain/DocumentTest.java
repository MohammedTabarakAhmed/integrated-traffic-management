package gov.itms.document.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("CC-080")
class DocumentTest {

    private static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");
    private static final Actor OFFICER = new Actor("u-officer", "officer");

    private static Document pending() {
        return Document.create(UUID.randomUUID(), new Document.Spec("IDENTITY_DOCUMENT", "CUSTOMER", "c-1",
                "Passport", Document.Classification.CONFIDENTIAL, LocalDate.of(2030, 1, 1)), OFFICER, NOW);
    }

    @Test
    @DisplayName("CC-080: content is received once; the document becomes available with its type, size and hash")
    void availableOnce() {
        Document d = pending();
        assertThat(d.status()).isEqualTo(Document.Status.PENDING_UPLOAD);
        assertThatThrownBy(d::requireAvailable).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("DOCUMENT_NOT_AVAILABLE");
        d.available("application/pdf", 1234, "ab".repeat(32), "documents/" + d.id(), "CLEAN", NOW);
        assertThat(d.status()).isEqualTo(Document.Status.AVAILABLE);
        assertThat(d.size()).isEqualTo(1234);
        assertThat(d.objectKey()).isEqualTo("documents/" + d.id());
        assertThat(d.availableAt()).isEqualTo(NOW);
        d.requireAvailable();
        assertThatThrownBy(() -> d.available("application/pdf", 1, "x", "k", "CLEAN", NOW))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("CONTENT_ALREADY_UPLOADED");
    }

    @Test
    @Tag("CC-081")
    @DisplayName("CC-081: quarantined content is never available and cannot be replaced")
    void quarantine() {
        Document d = pending();
        d.quarantined("application/pdf", 68, "cd".repeat(32), "Eicar-Signature");
        assertThat(d.status()).isEqualTo(Document.Status.QUARANTINED);
        assertThat(d.objectKey()).isNull();
        assertThat(d.scanResult()).isEqualTo("Eicar-Signature");
        assertThatThrownBy(d::requireAvailable).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("DOCUMENT_NOT_AVAILABLE");
        assertThatThrownBy(d::requirePendingUpload).isInstanceOf(DomainException.class).extracting("code")
                .isEqualTo("CONTENT_ALREADY_UPLOADED");
    }

    @Test
    @Tag("CC-085")
    @DisplayName("CC-080, CC-085: types, owners, titles and linked records are checked")
    void fields() {
        assertThatThrownBy(() -> new Document.Spec("passport", "CUSTOMER", "c-1", null, null, null))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> new Document.Spec("PASSPORT", "CUSTOMER", "c 1", null, null, null))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThatThrownBy(() -> new Document.Spec("PASSPORT", "CUSTOMER", "c-1", "a\u0000b", null, null))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
        assertThat(new Document.Link("ACCIDENT_CASE", "ACC-2026-1", OFFICER, NOW).recordId()).isEqualTo("ACC-2026-1");
        assertThatThrownBy(() -> new Document.Link("accident", "ACC-1", OFFICER, NOW))
                .isInstanceOf(DomainException.class).extracting("code").isEqualTo("FIELD_INVALID");
    }
}
