package za.gov.govaccess.ai.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import za.gov.govaccess.ai.dto.AiAttachment;
import za.gov.govaccess.ai.dto.AiRequest;
import za.gov.govaccess.ai.enums.AiRedactionMode;

@Component
public class RegexPiiRedactor implements PiiRedactor {
    private static final Pattern ID = Pattern.compile("(?<![0-9])[0-9]{13}(?![0-9])");
    private static final Pattern EMAIL = Pattern.compile("[a-z0-9.!#$%&'*+/=?^_{|}~-]+@[a-z0-9-]+(?:\\.[a-z0-9-]+)+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])(?:\\+27[ .-]?|0027[ .-]?|0)[1-9](?:[ .()-]?[0-9]){8}(?![0-9])");
    private static final Pattern ACCOUNT = Pattern.compile("(?<![0-9])[0-9](?:[ -]?[0-9]){7,11}(?![0-9])");

    @Override
    public AiRequest redact(AiRequest request, AiRedactionMode mode) {
        String system = mask(request.systemPrompt());
        String user = mask(request.userContent());
        boolean detected = !system.equals(request.systemPrompt()) || !user.equals(request.userContent());
        var attachments = new ArrayList<AiAttachment>();
        for (var attachment : request.attachments()) {
            if (attachment.mimeType().toLowerCase(Locale.ROOT).startsWith("text/")) {
                String original = new String(attachment.bytes(), StandardCharsets.UTF_8);
                String masked = mask(original);
                detected |= !masked.equals(original);
                attachments.add(new AiAttachment(masked.getBytes(StandardCharsets.UTF_8), attachment.mimeType()));
            } else {
                attachments.add(attachment);
            }
        }
        // Schemas are structural contracts and cannot safely be rewritten.
        if (request.jsonSchema() != null) {
            String schema = request.jsonSchema().toString();
            if (!mask(schema).equals(schema)) {
                return null;
            }
        }
        return mode == AiRedactionMode.BLOCK && detected ? null : request.withContent(system, user, attachments);
    }

    private static String mask(String value) {
        String masked = ID.matcher(value).replaceAll(match -> luhn(match.group()) ? "[ID]" : match.group());
        masked = EMAIL.matcher(masked).replaceAll("[EMAIL]");
        masked = PHONE.matcher(masked).replaceAll("[PHONE]");
        return ACCOUNT.matcher(masked).replaceAll("[ACCOUNT]");
    }

    private static boolean luhn(String digits) {
        int sum = 0;
        for (int index = digits.length() - 1; index >= 0; index--) {
            int digit = digits.charAt(index) - '0';
            if ((digits.length() - 1 - index) % 2 == 1) {
                digit *= 2;
            }
            sum += digit > 9 ? digit - 9 : digit;
        }
        return sum % 10 == 0;
    }
}
