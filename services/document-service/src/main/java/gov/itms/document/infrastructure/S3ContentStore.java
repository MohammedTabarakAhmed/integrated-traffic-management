package gov.itms.document.infrastructure;

import gov.itms.document.application.Ports;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The object store through the S3 API (ADR-005): Ceph RGW in production, MinIO locally. Path-style requests signed
 * with AWS Signature Version 4, using only the JDK (no cloud SDK). Objects are written with their SHA-256 as the signed
 * payload hash, so the store rejects a body altered in transit (DAT-021). Only PUT and GET of whole objects are used.
 */
class S3ContentStore implements Ports.ContentStore {

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final HttpClient http;
    private final URI endpoint;
    private final String bucket;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final Duration timeout;
    private final Clock clock;

    S3ContentStore(URI endpoint, String bucket, String region, String accessKey, String secretKey, Duration timeout,
                   Clock clock) {
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.region = region;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public void put(String key, Path file, String mediaType, String sha256Hex) {
        try {
            send(signed("PUT", key, sha256Hex, mediaType, HttpRequest.BodyPublishers.ofFile(file)),
                    HttpResponse.BodyHandlers.discarding());
        } catch (FileNotFoundException e) {
            throw new Ports.StoreUnavailableException("upload file vanished", e);
        }
    }

    @Override
    public void put(String key, byte[] content, String mediaType, String sha256Hex) {
        send(signed("PUT", key, sha256Hex, mediaType, HttpRequest.BodyPublishers.ofByteArray(content)),
                HttpResponse.BodyHandlers.discarding());
    }

    @Override
    public InputStream get(String key) {
        return send(signed("GET", key, sha256(new byte[0]), null, HttpRequest.BodyPublishers.noBody()),
                HttpResponse.BodyHandlers.ofInputStream());
    }

    private <T> T send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        HttpResponse<T> response;
        try {
            response = http.send(request, handler);
        } catch (IOException e) {
            throw new Ports.StoreUnavailableException("object store did not answer", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Ports.StoreUnavailableException("interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            if (response.body() instanceof InputStream in) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // the connection is released either way
                }
            }
            throw new Ports.StoreUnavailableException("object store answered " + response.statusCode(), null);
        }
        return response.body();
    }

    private HttpRequest signed(String method, String key, String payloadHash, String mediaType,
                               HttpRequest.BodyPublisher body) {
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String amzDate = AMZ_DATE.format(now);
        String date = DATE.format(now);
        String path = "/" + encode(bucket) + "/" + encodePath(key);
        String host = endpoint.getHost() + (endpoint.getPort() == -1 ? "" : ":" + endpoint.getPort());
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonical = method + "\n" + path + "\n\n"
                + "host:" + host + "\n" + "x-amz-content-sha256:" + payloadHash + "\n" + "x-amz-date:" + amzDate
                + "\n\n" + signedHeaders + "\n" + payloadHash;
        String scope = date + "/" + region + "/s3/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256(canonical.getBytes(StandardCharsets.UTF_8));
        byte[] signingKey = hmac(hmac(hmac(hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date),
                region), "s3"), "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(signingKey, toSign));
        HttpRequest.Builder b = HttpRequest.newBuilder(endpoint.resolve(path)).timeout(timeout)
                .header("x-amz-date", amzDate)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                        + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature)
                .method(method, body);
        if (mediaType != null) {
            b.header("Content-Type", mediaType);
        }
        return b.build();
    }

    private static String encodePath(String key) {
        StringBuilder out = new StringBuilder();
        for (String segment : key.split("/", -1)) {
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(encode(segment));
        }
        return out.toString();
    }

    /** RFC 3986 unreserved characters stay; everything else is percent-encoded (SigV4 rules). */
    private static String encode(String s) {
        StringBuilder out = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_'
                    || c == '.' || c == '~') {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return out.toString();
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
