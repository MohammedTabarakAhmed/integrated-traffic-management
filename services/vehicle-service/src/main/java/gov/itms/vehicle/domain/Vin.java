package gov.itms.vehicle.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Vehicle identification number checks (VL-003): 17 characters of A-Z (without I, O, Q) and 0-9, and the ISO 3779 /
 * North American check digit in position 9 when the check is switched on for the VIN's region of manufacture.
 */
public final class Vin {

    private static final Pattern FORMAT = Pattern.compile("^[A-HJ-NPR-Z0-9]{17}$");
    private static final int[] WEIGHTS = {8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2};

    private Vin() {
    }

    /**
     * Checks a VIN. {@code checkDigitRegions} lists the first characters (world manufacturer region) whose VINs carry a
     * check digit (for example 1-5 for North America); an empty list checks every VIN.
     */
    public static String check(String vin, List<String> checkDigitRegions) {
        String v = vin == null ? "" : vin.strip().toUpperCase(java.util.Locale.ROOT);
        if (!FORMAT.matcher(v).matches()) {
            throw DomainException.validation("VIN_INVALID",
                    "a VIN has 17 characters, letters except I, O and Q, and digits", List.of("VL-003"));
        }
        boolean applies = checkDigitRegions.isEmpty() || checkDigitRegions.contains(v.substring(0, 1));
        if (applies && checkDigit(v) != v.charAt(8)) {
            throw DomainException.validation("VIN_CHECK_DIGIT", "the VIN's check digit is wrong", List.of("VL-003"));
        }
        return v;
    }

    static char checkDigit(String vin) {
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += value(vin.charAt(i)) * WEIGHTS[i];
        }
        int r = sum % 11;
        return r == 10 ? 'X' : (char) ('0' + r);
    }

    private static int value(char c) {
        if (Character.isDigit(c)) {
            return c - '0';
        }
        return switch (c) {
            case 'A', 'J' -> 1;
            case 'B', 'K', 'S' -> 2;
            case 'C', 'L', 'T' -> 3;
            case 'D', 'M', 'U' -> 4;
            case 'E', 'N', 'V' -> 5;
            case 'F', 'W' -> 6;
            case 'G', 'P', 'X' -> 7;
            case 'H', 'Y' -> 8;
            case 'R', 'Z' -> 9;
            default -> throw new IllegalArgumentException("not a VIN character: " + c);
        };
    }
}
