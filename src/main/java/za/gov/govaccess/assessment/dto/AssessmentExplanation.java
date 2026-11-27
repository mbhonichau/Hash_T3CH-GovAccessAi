package za.gov.govaccess.assessment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AssessmentExplanation(@NotBlank @Size(max = 1000) String summary,
                                    @NotNull List<@Valid RuleExplanation> ruleExplanations,
                                    @NotNull @Size(min = 2, max = 3) List<@NotBlank @Size(max = 300) String> caseworkerFocusPoints) {
    public record RuleExplanation(@NotBlank String ruleCode, @NotBlank @Size(max = 800) String statement,
                                  @NotNull List<@NotBlank String> evidenceUsed, @NotBlank @Size(max = 400) String nextStep) {}
}
