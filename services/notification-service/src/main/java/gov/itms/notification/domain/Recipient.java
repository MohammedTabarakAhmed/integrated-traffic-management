package gov.itms.notification.domain;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A recipient's contact points, language and channel preferences per category (CC-073; ADR-050). The recipient id is
 * the customer's login subject (Q-066). Without preferences for a category every channel of the template is used;
 * mandatory legal notices ignore preferences. The inbox is always available.
 */
public record Recipient(String id, Language language, String phone, String email, String pushToken,
                        Map<String, Set<DeliveryChannel>> preferences) {

    private static final Pattern PHONE = Pattern.compile("^\\+[1-9][0-9]{6,14}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,190}\\.[A-Za-z]{2,24}$");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");
    private static final Pattern CATEGORY = Pattern.compile(Template.CODE);

    public Recipient {
        if (id == null || !ID.matcher(id).matches()) {
            throw Template.invalid("recipientId");
        }
        language = language == null ? Language.NL : language;
        if (phone != null && !PHONE.matcher(phone).matches()) {
            throw Template.invalid("phone");
        }
        if (email != null && (email.length() > 254 || !EMAIL.matcher(email).matches())) {
            throw Template.invalid("email");
        }
        if (pushToken != null && (pushToken.isBlank() || pushToken.length() > 500)) {
            throw Template.invalid("pushToken");
        }
        Map<String, Set<DeliveryChannel>> copy = new LinkedHashMap<>();
        if (preferences != null) {
            if (preferences.size() > 50) {
                throw Template.invalid("preferences");
            }
            preferences.forEach((category, channels) -> {
                if (category == null || !CATEGORY.matcher(category).matches()) {
                    throw Template.invalid("preferences category");
                }
                copy.put(category, channels == null || channels.isEmpty() ? Set.of()
                        : Set.copyOf(EnumSet.copyOf(channels)));
            });
        }
        preferences = Map.copyOf(copy);
    }

    public static Recipient unknown(String id) {
        return new Recipient(id, Language.NL, null, null, null, Map.of());
    }

    /** The channels to use for a template (CC-073): preferences unless mandatory, and only reachable channels. */
    public Set<DeliveryChannel> channelsFor(Template template) {
        Set<DeliveryChannel> wanted = EnumSet.noneOf(DeliveryChannel.class);
        wanted.addAll(template.contents().keySet());
        Set<DeliveryChannel> preferred = preferences.get(template.category());
        if (!template.mandatory() && preferred != null) {
            wanted.retainAll(preferred);
        }
        wanted.removeIf(c -> destination(c) == null);
        return wanted;
    }

    /** Where a channel delivers to, or {@code null} when the recipient has no such contact point. */
    public String destination(DeliveryChannel channel) {
        return switch (channel) {
            case SMS -> phone;
            case EMAIL -> email;
            case PUSH -> pushToken;
            case INBOX -> id;
        };
    }

    /** Masks a destination for display (SEC-014): the last characters only. */
    public static String mask(DeliveryChannel channel, String destination) {
        if (destination == null) {
            return null;
        }
        return switch (channel) {
            case SMS -> "*".repeat(Math.max(0, destination.length() - 3)) + destination.substring(
                    Math.max(0, destination.length() - 3));
            case EMAIL -> destination.charAt(0) + "***" + destination.substring(destination.indexOf('@'));
            case PUSH -> "device";
            case INBOX -> destination;
        };
    }

}
