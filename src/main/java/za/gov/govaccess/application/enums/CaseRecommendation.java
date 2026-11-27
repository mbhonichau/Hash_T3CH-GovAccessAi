package za.gov.govaccess.application.enums;

public enum CaseRecommendation {
    LIKELY_ELIGIBLE, NEEDS_REVIEW, NOT_ELIGIBLE;

    public String displayLabel() {
        return switch (this) {
            case LIKELY_ELIGIBLE -> "Likely eligible";
            case NEEDS_REVIEW -> "Needs review";
            case NOT_ELIGIBLE -> "Not eligible recommendation · awaiting an official decision";
        };
    }
}
