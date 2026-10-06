package gov.itms.notification.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A bilingual notification template (CC-070; ADR-050): content per delivery channel in the national language and in
 * English, with {@code {{placeholder}}} fields filled from the notification's data. {@code mandatory} marks legal
 * notices that are sent whatever the customer's preferences (CC-073). Publishing creates a new immutable version; a
 * notification records the version it was rendered from.
 */
public record Template(String code, int versionNo, String category, boolean mandatory,
                       Map<DeliveryChannel, Content> contents, Actor publishedBy, Instant publishedAt) {

    public static final String CODE = "^[A-Z][A-Z0-9_]{1,59}$";
    private static final Pattern CODE_PATTERN = Pattern.compile(CODE);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9]{0,39})}}");

    /** One channel's text. SMS and push have no subject; email and inbox need one. */
    public record Content(String subjectNl, String subjectEn, String bodyNl, String bodyEn) {
    }

    public Template {
        if (code == null || !CODE_PATTERN.matcher(code).matches()) {
            throw invalid("code");
        }
        if (category == null || !CODE_PATTERN.matcher(category).matches()) {
            throw invalid("category");
        }
        if (contents == null || contents.isEmpty()) {
            throw invalid("contents");
        }
        Map<DeliveryChannel, Content> copy = new EnumMap<>(DeliveryChannel.class);
        contents.forEach((channel, c) -> {
            Objects.requireNonNull(channel);
            boolean needsSubject = channel == DeliveryChannel.EMAIL || channel == DeliveryChannel.INBOX;
            int max = channel == DeliveryChannel.SMS ? 1000 : channel == DeliveryChannel.PUSH ? 500 : 10000;
            text(channel + " bodyNl", c.bodyNl(), max);
            text(channel + " bodyEn", c.bodyEn(), max);
            if (needsSubject) {
                text(channel + " subjectNl", c.subjectNl(), 200);
                text(channel + " subjectEn", c.subjectEn(), 200);
            } else if (c.subjectNl() != null || c.subjectEn() != null) {
                throw DomainException.validation("TEMPLATE_INVALID", channel + " has no subject", List.of("CC-070"),
                        channel.name());
            }
            copy.put(channel, c);
        });
        contents = Map.copyOf(copy);
        Objects.requireNonNull(publishedBy);
        Objects.requireNonNull(publishedAt);
    }

    /** The rendered subject and body in the language; a placeholder without data is refused. */
    public Rendered render(DeliveryChannel channel, Language language, Map<String, String> data) {
        Content c = contents.get(channel);
        if (c == null) {
            throw new IllegalArgumentException("template " + code + " has no " + channel + " content");
        }
        String subject = language == Language.EN ? c.subjectEn() : c.subjectNl();
        String body = language == Language.EN ? c.bodyEn() : c.bodyNl();
        return new Rendered(subject == null ? null : fill(subject, data), fill(body, data));
    }

    public record Rendered(String subject, String body) {
    }

    private String fill(String text, Map<String, String> data) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = data.get(m.group(1));
            if (value == null) {
                throw DomainException.validation("TEMPLATE_DATA_MISSING",
                        "template " + code + " needs the value " + m.group(1), List.of("CC-070"), m.group(1));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static void text(String field, String value, int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(ch -> Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t')) {
            throw DomainException.validation("TEMPLATE_INVALID", field + " is missing or too long",
                    List.of("CC-070"), field);
        }
    }

    static DomainException invalid(String field) {
        return DomainException.validation("FIELD_INVALID", field + " is missing or malformed", List.of("CC-070"),
                field);
    }
}
