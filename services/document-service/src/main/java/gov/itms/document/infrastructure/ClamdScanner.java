package gov.itms.document.infrastructure;

import gov.itms.document.application.Ports;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Malware scanning with ClamAV's clamd over TCP, {@code INSTREAM} command (CC-081): the file is streamed in chunks,
 * clamd answers {@code stream: OK} or {@code stream: <name> FOUND}. Any other answer, or no answer within the
 * timeout, means no verdict: the upload is refused rather than stored unscanned.
 */
class ClamdScanner implements Ports.MalwareScanner {

    private static final int CHUNK = 64 * 1024;

    private final String host;
    private final int port;
    private final Duration timeout;

    ClamdScanner(String host, int port, Duration timeout) {
        this.host = host;
        this.port = port;
        this.timeout = timeout;
    }

    @Override
    public Result scan(Path file) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            socket.setSoTimeout((int) timeout.toMillis());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[CHUNK];
            try (InputStream in = Files.newInputStream(file)) {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.writeInt(n);
                    out.write(buffer, 0, n);
                }
            }
            out.writeInt(0);
            out.flush();
            String answer = readAnswer(socket.getInputStream());
            if (answer.endsWith("OK") && !answer.contains("FOUND")) {
                return new Result(true, null);
            }
            if (answer.endsWith("FOUND")) {
                String finding = answer.substring(answer.indexOf(':') + 1, answer.length() - "FOUND".length()).strip();
                return new Result(false, finding.isEmpty() ? "malware" : finding.substring(0,
                        Math.min(finding.length(), 200)));
            }
            throw new Ports.ScannerUnavailableException("unexpected scanner answer", null);
        } catch (IOException e) {
            throw new Ports.ScannerUnavailableException("malware scanner did not answer", e);
        }
    }

    private static String readAnswer(InputStream in) throws IOException {
        ByteArrayOutputStream answer = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != 0) {
            if (answer.size() > 1024) {
                break;
            }
            answer.write(b);
        }
        return answer.toString(StandardCharsets.US_ASCII).strip();
    }
}
