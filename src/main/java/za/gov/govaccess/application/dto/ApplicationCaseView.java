package za.gov.govaccess.application.dto;

import java.time.Instant;
import java.util.UUID;
import za.gov.govaccess.application.enums.ApplicationStatus;
import za.gov.govaccess.application.enums.CaseRecommendation;

public record ApplicationCaseView(UUID id, String reference, String applicantName, String programmeName,
                                  ApplicationStatus status, CaseRecommendation recommendation,
                                  String summary, String createdBy, Instant createdAt) {}
