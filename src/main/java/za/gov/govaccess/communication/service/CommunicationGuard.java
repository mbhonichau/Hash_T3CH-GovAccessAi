package za.gov.govaccess.communication.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import za.gov.govaccess.ai.service.AiOutputInvalidException;

public final class CommunicationGuard {
    private static final Pattern NEUTRAL = Pattern.compile(
            "(?i)\\b(?:fr[a]ud(?:ulent)?|sus[p]icious|dishonest(?:y|ly)?|lied|lying|deceptive)\\b");
    private static final Pattern VALUES = Pattern.compile(
            "\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|[+-]?\\d+(?:[.,]\\d+)*(?:[eE][+-]?\\d+)?");
    private static final Pattern RULE = Pattern.compile("\\[([^\\]\\r\\n]+)]");

    private CommunicationGuard() {}

    public static void neutral(String text) {
        if (NEUTRAL.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).find()) {
            invalid("Use neutral language about the evidence");
        }
    }

    public static void onlyRecordedValues(String input, String output) {
        neutral(output);
        var recorded = values(input).keySet();
        if (!recorded.containsAll(values(output).keySet())) invalid("Use the recorded amounts and dates");
    }

    public static void translation(String english, String translated, Map<String, String> glossary) {
        neutral(translated);
        if (!values(english).equals(values(translated)) || !tokens(RULE, english).equals(tokens(RULE, translated))) {
            invalid("Preserve every number, date and bracketed rule code");
        }
        glossary.forEach((source, target) -> {
            if (containsTerm(english, source) && !containsTerm(translated, target)) {
                invalid("Use the configured glossary terms");
            }
        });
    }

    private static boolean containsTerm(String text, String term) {
        return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(term) + "(?![\\p{L}\\p{N}])")
                .matcher(text).find();
    }

    private static Map<String, Long> values(String text) {
        return VALUES.matcher(text).results().map(match -> match.group().replace(",", ""))
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
    }

    private static Map<String, Long> tokens(Pattern pattern, String text) {
        return pattern.matcher(text).results().map(match -> match.group(1))
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()));
    }

    public static double grade(String text) {
        var words = Pattern.compile("[a-zA-Z]+").matcher(text).results().map(match -> match.group().toLowerCase(Locale.ROOT)).toList();
        if (words.isEmpty()) return 0;
        long sentences = Math.max(1, Pattern.compile("[.!?]+(?=\\s|$)").matcher(text).results().count());
        long syllables = words.stream().mapToLong(word -> {
            String stem = word.length() > 2 ? word.replaceFirst("(?<=[^aeiouy])e$", "") : word;
            return Math.max(1, Pattern.compile("[aeiouy]+").matcher(stem).results().count());
        }).sum();
        return Math.max(0, 0.39 * words.size() / sentences + 11.8 * syllables / words.size() - 15.59);
    }

    public static void invalid(String message) { throw new AiOutputInvalidException(List.of(message)); }
}
