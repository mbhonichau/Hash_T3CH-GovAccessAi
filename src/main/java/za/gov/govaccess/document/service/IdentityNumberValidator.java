package za.gov.govaccess.document.service;

import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.regex.Pattern;

public final class IdentityNumberValidator {
    private static final Pattern ID = Pattern.compile("(?<![0-9])[0-9]{13}(?![0-9])");
    private IdentityNumberValidator() {}

    public record Reference(String masked, boolean valid) {}

    public static Reference find(String text, LocalDate today) {
        var matches = ID.matcher(text);
        if (!matches.find()) return new Reference(null, false);
        String digits = matches.group();
        if (matches.find()) return new Reference(null, false);
        return new Reference("*********" + digits.substring(9), valid(digits, today));
    }

    public static boolean valid(String digits, LocalDate today) {
        if (digits == null || !ID.matcher(digits).matches()) return false;
        int sum = 0;
        for (int i = 0; i < 13; i++) {
            int digit = digits.charAt(i) - '0';
            if (i % 2 == 1) digit *= 2;
            sum += digit > 9 ? digit - 9 : digit;
        }
        if (sum % 10 != 0) return false;
        int year = Integer.parseInt(digits.substring(0, 2));
        int month = Integer.parseInt(digits.substring(2, 4));
        int day = Integer.parseInt(digits.substring(4, 6));
        for (int century : new int[]{2000, 1900}) {
            try {
                if (!LocalDate.of(century + year, month, day).isAfter(today)) return true;
            } catch (DateTimeException ignored) {
                // Check the other century for a leap date.
            }
        }
        return false;
    }
}
