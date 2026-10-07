package gov.itms.document.infrastructure;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real TCP stand-in for clamd's {@code zINSTREAM} protocol (CC-081): it reads the length-prefixed chunks and answers
 * {@code stream: Eicar-Test-Signature FOUND} when the stream contains the EICAR test string, {@code stream: OK}
 * otherwise. {@link #mode} can make it answer nonsense or close without an answer.
 */
public final class ClamdStub implements AutoCloseable {

    /** The standard EICAR anti-malware test string (harmless by design). */
    public static final String EICAR = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    public enum Mode { NORMAL, GARBAGE, SILENT }

    public volatile Mode mode = Mode.NORMAL;
    public final AtomicInteger scans = new AtomicInteger();
    private final ServerSocket server;
    private final Thread acceptor;

    public ClamdStub() {
        try {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        acceptor = Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    Thread.ofVirtual().start(() -> handle(s));
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    public int port() {
        return server.getLocalPort();
    }

    private void handle(Socket socket) {
        try (socket) {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            byte[] command = new byte["zINSTREAM\0".length()];
            in.readFully(command);
            if (!"zINSTREAM\0".equals(new String(command, StandardCharsets.US_ASCII))) {
                return;
            }
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            int length;
            while ((length = in.readInt()) > 0) {
                byte[] chunk = new byte[length];
                in.readFully(chunk);
                content.write(chunk);
            }
            scans.incrementAndGet();
            String answer = switch (mode) {
                case GARBAGE -> "stream: what?";
                case SILENT -> null;
                case NORMAL -> content.toString(StandardCharsets.ISO_8859_1).contains(EICAR)
                        ? "stream: Eicar-Test-Signature FOUND" : "stream: OK";
            };
            if (answer != null) {
                OutputStream out = socket.getOutputStream();
                out.write((answer + "\0").getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } catch (IOException ignored) {
            // the client went away
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
        acceptor.interrupt();
    }
}
