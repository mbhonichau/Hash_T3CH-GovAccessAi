package za.gov.govaccess.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ApplicationIntakeForm {
    @NotBlank @Size(max = 200)
    private String applicantName;
    @NotBlank
    private String programmeCode = "NSFAS_BURSARY";
    @NotBlank @Size(max = 2000)
    private String summary;

    public String getApplicantName() { return applicantName; }
    public void setApplicantName(String applicantName) { this.applicantName = applicantName; }
    public String getProgrammeCode() { return programmeCode; }
    public void setProgrammeCode(String programmeCode) { this.programmeCode = programmeCode; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
}
