package za.gov.govaccess.assessment.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import za.gov.govaccess.rules.service.RecommendationService.Recommendation;

public record AssessmentRun(UUID id, LocalDate applicationDate, OffsetDateTime assessedAt,
                            UUID policyVersionId, String policyVersionLabel, String policyHash,
                            Recommendation recommendation) {}
