package za.gov.govaccess.assessment.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import za.gov.govaccess.assessment.dto.AssessmentRun;
import za.gov.govaccess.assessment.service.PublishedPolicyAssessmentService;
import za.gov.govaccess.rules.dto.VerifiedRuleEvidence;

@RestController
@RequestMapping("/api/assessments")
class AssessmentRunController {
    private final PublishedPolicyAssessmentService assessments;

    AssessmentRunController(PublishedPolicyAssessmentService assessments) {
        this.assessments = assessments;
    }

    record Request(@NotBlank @Size(max = 100) String programmeCode, @NotNull LocalDate applicationDate,
                   @NotNull @Size(max = 100) List<@NotNull VerifiedRuleEvidence> verifiedEvidence,
                   boolean manualReview) {}

    @PostMapping
    ResponseEntity<AssessmentRun> run(Authentication actor, @Valid @RequestBody Request input) {
        var run = assessments.run(actor, input.programmeCode(), input.applicationDate(), input.verifiedEvidence(),
                        input.manualReview())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "No published policy applies on this application date"));
        return ResponseEntity.created(URI.create("/api/assessments/" + run.id())).body(run);
    }

    @GetMapping("/{id}")
    AssessmentRun find(Authentication actor, @PathVariable UUID id) {
        return assessments.find(actor, id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
