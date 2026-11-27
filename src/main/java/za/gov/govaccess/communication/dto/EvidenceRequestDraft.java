package za.gov.govaccess.communication.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record EvidenceRequestDraft(@NotBlank @Size(max = 800) String officerSummary,
                                   @NotBlank @Size(max = 3000) String applicantMessage,
                                   @NotEmpty List<@Valid RequestedItem> requestedItems) {
    public record RequestedItem(@NotBlank String ruleCode, List<String> documentNames) {}
}
