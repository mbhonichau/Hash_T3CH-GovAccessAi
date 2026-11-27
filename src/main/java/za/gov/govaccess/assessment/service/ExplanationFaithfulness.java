package za.gov.govaccess.assessment.service;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import za.gov.govaccess.ai.service.AiOutputInvalidException;
import za.gov.govaccess.assessment.dto.AssessmentExplanation;
import za.gov.govaccess.assessment.dto.AssessmentExplanationInput;
import za.gov.govaccess.assessment.dto.AssessmentExplanationInput.EvaluatedRule;

public final class ExplanationFaithfulness {
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(?:approved|rejected|you[\\s\\p{P}]+qualify|guaranteed|entitled|fr[a]ud(?:ulent)?|sus[p]icious"
                    + "|dishonest(?:y|ly)?|lied|lying|deceptive)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final String MONTHS = "(?:January|February|March|April|May|June|July|August|September|October|November|December)";
    private static final Pattern NUMBERS = Pattern.compile(
            "\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}|"
                    + "\\d{1,2}\\s+" + MONTHS + "\\s+\\d{4}|" + MONTHS + "\\s+\\d{1,2},?\\s+\\d{4}"
                    + "|[+-]?\\d+(?:[.,]\\d+)*(?:[eE][+-]?\\d+)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECOMMENDATION = Pattern.compile(
            "(?i)\\b(LIKELY[ _-]ELIGIBLE|NOT[ _-]ELIGIBLE|NEEDS[ _-]REVIEW)\\b");
    private static final Pattern VERSION = Pattern.compile("(?i)\\bpolicy version\\s*:");

    private ExplanationFaithfulness() {}

    public static void verify(AssessmentExplanationInput input, String persistedJson, AssessmentExplanation explanation) {
        if (!explanation.summary().startsWith("Recommendation: " + input.recommendation() + ".")) {
            fail("Start the summary with the exact recorded recommendation");
        }
        String exactVersion = "Policy version: \"" + input.policyVersion() + "\".";
        if (!explanation.summary().contains(exactVersion)) fail("Quote the exact policy version");
        var expected = input.rules().stream().collect(Collectors.toMap(EvaluatedRule::ruleCode, Function.identity()));
        var seen = new HashSet<String>();
        for (var item : explanation.ruleExplanations()) {
            var rule = expected.get(item.ruleCode());
            if (rule == null || !seen.add(item.ruleCode())) fail("Use each recorded rule code exactly once");
            if (!item.statement().startsWith(rule.status().name() + ":")) fail("Use the recorded rule status");
            if (!item.statement().contains(rule.ruleCode()) || !item.statement().contains("\"" + input.policyVersion() + "\"")) {
                fail("Name the rule and quote the recorded policy version in its statement");
            }
            if (rule.sources().stream().anyMatch(source -> !item.statement().contains(source.citation()))) {
                fail("Cite every recorded source document and page in its rule statement");
            }
            if (item.evidenceUsed().size() != rule.evidenceUsed().size()
                    || !new HashSet<>(item.evidenceUsed()).equals(new HashSet<>(rule.evidenceUsed()))) {
                fail("Use exactly the recorded evidence references for this rule");
            }
        }
        if (!seen.equals(expected.keySet())) fail("Cover all recorded rules");
        String prose = Stream.concat(Stream.of(explanation.summary()), Stream.concat(
                explanation.caseworkerFocusPoints().stream(),
                explanation.ruleExplanations().stream().flatMap(item -> Stream.of(item.statement(), item.nextStep()))))
                .collect(Collectors.joining("\n"));
        var normal = Normalizer.normalize(prose, Normalizer.Form.NFKC);
        if (FORBIDDEN.matcher(normal).find()) fail("Describe a recommendation awaiting an official decision");
        var numbers = NUMBERS.matcher(normal);
        var allowed = NUMBERS.matcher(persistedJson).results().map(match -> match.group().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (numbers.results().anyMatch(match -> !allowed.contains(match.group().toLowerCase(Locale.ROOT)))) {
            fail("Use only numbers and dates present in the recorded assessment");
        }
        if (RECOMMENDATION.matcher(normal).results().anyMatch(match ->
                !input.recommendation().equals(match.group(1).toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_')))) {
            fail("Use only the recorded recommendation");
        }
        if (VERSION.matcher(normal.replace(Normalizer.normalize(exactVersion, Normalizer.Form.NFKC), "")).find()) {
            fail("Use only the recorded policy version");
        }
    }

    private static void fail(String reason) { throw new AiOutputInvalidException(List.of(reason)); }
}
