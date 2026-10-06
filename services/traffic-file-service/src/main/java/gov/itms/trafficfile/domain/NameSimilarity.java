package gov.itms.trafficfile.domain;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Fuzzy name comparison for probable duplicate files (DL-009): Jaro-Winkler similarity of the normalised full names
 * (given + family), compared in the national language and in English, taking the better of the two. Normalising
 * removes diacritics and Arabic short vowels, folds case and collapses spaces and punctuation, so "Al-Mansoori" and
 * "Al Mansoori" match. A pure function; the threshold is configuration ({@code itms.traffic-file.duplicates}).
 */
public final class NameSimilarity {

    private NameSimilarity() {
    }

    /** Similarity of two people's names, 0 (nothing alike) to 1 (identical after normalising). */
    public static double of(PersonName a, PersonName b) {
        double nl = compare(full(a.givenNl(), a.familyNl()), full(b.givenNl(), b.familyNl()));
        double en = compare(full(a.givenEn(), a.familyEn()), full(b.givenEn(), b.familyEn()));
        return Math.max(nl, en);
    }

    static String normalise(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder(decomposed.length());
        boolean space = false;
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            int type = Character.getType(c);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                    || (c >= 'ً' && c <= 'ٟ') || c == 'ـ') {
                continue; // diacritics, Arabic harakat and tatweel
            }
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
                space = false;
            } else if (!space && !out.isEmpty()) {
                out.append(' ');
                space = true;
            }
        }
        return out.toString().strip().toLowerCase(Locale.ROOT);
    }

    private static String full(String given, String family) {
        if (given == null || family == null) {
            return null;
        }
        return normalise(given + " " + family);
    }

    private static double compare(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        return jaroWinkler(a, b);
    }

    static double jaroWinkler(String a, String b) {
        if (a.equals(b)) {
            return 1;
        }
        int window = Math.max(0, Math.max(a.length(), b.length()) / 2 - 1);
        boolean[] matchedA = new boolean[a.length()];
        boolean[] matchedB = new boolean[b.length()];
        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(b.length() - 1, i + window);
            for (int j = from; j <= to; j++) {
                if (!matchedB[j] && a.charAt(i) == b.charAt(j)) {
                    matchedA[i] = true;
                    matchedB[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0;
        }
        int transpositions = 0;
        for (int i = 0, j = 0; i < a.length(); i++) {
            if (matchedA[i]) {
                while (!matchedB[j]) {
                    j++;
                }
                if (a.charAt(i) != b.charAt(j)) {
                    transpositions++;
                }
                j++;
            }
        }
        double m = matches;
        double jaro = (m / a.length() + m / b.length() + (m - transpositions / 2.0) / m) / 3;
        int prefix = 0;
        while (prefix < Math.min(4, Math.min(a.length(), b.length())) && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }
}
