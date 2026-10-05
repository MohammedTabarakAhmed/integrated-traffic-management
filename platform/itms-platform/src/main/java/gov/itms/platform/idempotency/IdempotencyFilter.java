package gov.itms.platform.idempotency;

import gov.itms.platform.idempotency.IdempotencyKeys.Outcome;
import gov.itms.platform.idempotency.IdempotencyStore.StoredResponse;
import gov.itms.platform.web.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.support.RequestContextUtils;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;

/**
 * Enforces the {@code Idempotency-Key} header on the configured POST paths (API_SPECIFICATION §2, ADR-017, ADR-032).
 * Runs after authentication, so a key belongs to the caller who used it. A retry with the same key and the same
 * request gets the stored response again (header {@code Idempotent-Replayed: true}) instead of a second effect; the
 * same key with another request is refused with 409. Only successful (2xx) responses are stored: after a refusal or
 * an error the key is released and the client may retry with it.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");
    private static final List<String> REFS = List.of("FN-004");

    private final IdempotencyKeys keys;
    private final List<PathPattern> paths;
    private final MessageSource messages;
    private final ObjectMapper json;

    public IdempotencyFilter(IdempotencyKeys keys, List<String> paths, MessageSource messages, ObjectMapper json) {
        this.keys = keys;
        this.paths = paths.stream().map(PathPatternParser.defaultInstance::parse).toList();
        this.messages = messages;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        PathContainer path = PathContainer.parsePath(request.getRequestURI().substring(request.getContextPath().length()));
        return paths.stream().noneMatch(p -> p.matches(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            chain.doFilter(request, response); // security refuses it further on; nothing to remember
            return;
        }
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            writeProblem(request, response, HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                    "Send an Idempotency-Key header with this request, so that a retry is not processed twice.");
            return;
        }
        if (!SAFE_KEY.matcher(key).matches()) {
            writeProblem(request, response, HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
                    "The Idempotency-Key must be 8 to 128 characters using letters, digits, dot, underscore, colon or "
                            + "hyphen.");
            return;
        }
        byte[] body = request.getInputStream().readAllBytes();
        String principal = auth.getName();
        Outcome outcome = keys.claim(principal, key, hash(request, body));
        switch (outcome) {
            case Outcome.Replay replay -> replay(replay.response(), response);
            case Outcome.Mismatch mismatch -> writeProblem(request, response, HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used for a different request. "
                            + "Use a new key for a new request.");
            case Outcome.InProgress inProgress -> {
                response.setHeader(HttpHeaders.RETRY_AFTER, "2");
                writeProblem(request, response, HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                        "A request with this Idempotency-Key is still being processed. Try again shortly.");
            }
            case Outcome.Claimed claimed -> run(new CachedBodyRequest(request, body), response, chain, principal, key,
                    claimed.token());
        }
    }

    private void run(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String principal,
                     String key, String token) throws ServletException, IOException {
        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapper);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            release(principal, key, token);
            throw e;
        }
        int status = wrapper.getStatus();
        if (status >= 200 && status < 300) {
            try {
                keys.complete(principal, key, token, new StoredResponse(status, wrapper.getContentType(),
                        wrapper.getHeader(HttpHeaders.LOCATION), wrapper.getContentAsByteArray()));
            } catch (RuntimeException e) {
                // The business change is committed; a retry waits for the lock timeout, then the business rules
                // (CC-027, the lifecycle) refuse a second effect.
                log.error("Idempotency key could not be completed; the response is still returned", e);
            }
        } else {
            release(principal, key, token);
        }
        wrapper.copyBodyToResponse();
    }

    private void release(String principal, String key, String token) {
        try {
            keys.release(principal, key, token);
        } catch (RuntimeException e) {
            log.error("Idempotency key could not be released; it is freed after the lock timeout", e);
        }
    }

    private static void replay(StoredResponse stored, HttpServletResponse response) throws IOException {
        response.setStatus(stored.status());
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
        if (stored.location() != null) {
            response.setHeader(HttpHeaders.LOCATION, stored.location());
        }
        response.setHeader(REPLAYED_HEADER, "true");
        byte[] body = stored.body() == null ? new byte[0] : stored.body();
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    /** SHA-256 of method, path, query and body: the same key on another endpoint or with another body mismatches. */
    static String hash(HttpServletRequest request, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String query = request.getQueryString() == null ? "" : request.getQueryString();
            digest.update((request.getMethod() + '\n' + request.getRequestURI() + '\n' + query + '\n')
                    .getBytes(StandardCharsets.UTF_8));
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private void writeProblem(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
                              String fallback) throws IOException {
        String detail = messages.getMessage("problem." + code, null, fallback, RequestContextUtils.getLocale(request));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:itms:problem:" + code.toLowerCase().replace('_', '-'));
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("code", code);
        body.put("requirementRefs", REFS);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(body));
    }

    /** The request with its body already read, so it can be read again by the controller. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("synchronous reads only");
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding() == null ? StandardCharsets.UTF_8.name() : getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(), Charset.forName(encoding)));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
