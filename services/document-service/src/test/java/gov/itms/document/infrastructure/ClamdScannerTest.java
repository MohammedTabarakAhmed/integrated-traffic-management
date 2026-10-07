package gov.itms.document.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gov.itms.document.application.Ports;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("CC-081")
class ClamdScannerTest {

    private final ClamdStub clamd = new ClamdStub();
    private final ClamdScanner scanner = new ClamdScanner("127.0.0.1", clamd.port(), Duration.ofSeconds(2));

    @TempDir
    Path dir;

    @AfterEach
    void stop() throws IOException {
        clamd.close();
    }

    private Path file(String content) throws IOException {
        Path f = Files.createTempFile(dir, "scan", ".bin");
        Files.writeString(f, content, StandardCharsets.ISO_8859_1);
        return f;
    }

    @Test
    @DisplayName("CC-081: a clean file is OK; a file with the EICAR string is FOUND with its name")
    void cleanAndInfected() throws IOException {
        assertThat(scanner.scan(file("%PDF-1.7 harmless"))).isEqualTo(new Ports.MalwareScanner.Result(true, null));
        Ports.MalwareScanner.Result infected = scanner.scan(file("prefix " + ClamdStub.EICAR + " suffix"));
        assertThat(infected.clean()).isFalse();
        assertThat(infected.finding()).isEqualTo("Eicar-Test-Signature");
        assertThat(clamd.scans.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("CC-081: a file larger than one chunk is streamed whole")
    void largeFile() throws IOException {
        String big = "a".repeat(200_000) + ClamdStub.EICAR;
        assertThat(scanner.scan(file(big)).clean()).isFalse();
    }

    @Test
    @DisplayName("CC-081: no verdict (odd answer, no answer, nobody listening) refuses rather than passes")
    void noVerdict() throws IOException {
        Path f = file("data");
        clamd.mode = ClamdStub.Mode.GARBAGE;
        assertThatThrownBy(() -> scanner.scan(f)).isInstanceOf(Ports.ScannerUnavailableException.class);
        clamd.mode = ClamdStub.Mode.SILENT;
        assertThatThrownBy(() -> scanner.scan(f)).isInstanceOf(Ports.ScannerUnavailableException.class);
        int port = clamd.port();
        clamd.close();
        assertThatThrownBy(() -> new ClamdScanner("127.0.0.1", port, Duration.ofSeconds(1)).scan(f))
                .isInstanceOf(Ports.ScannerUnavailableException.class);
    }
}
