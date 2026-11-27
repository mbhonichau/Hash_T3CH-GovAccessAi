package za.gov.govaccess.communication;

import java.util.Map;
import org.junit.jupiter.api.Test;
import za.gov.govaccess.ai.service.AiOutputInvalidException;
import za.gov.govaccess.communication.service.CommunicationGuard;

import static org.assertj.core.api.Assertions.*;

class CommunicationGuardTests {
    @Test
    void checksTranslationNumbersDatesRuleCodesAndGlossary() {
        String english = "Notice [income-01]: R10,000, 2026-11-27, 10 days.";
        String translated = "Kennisgewing [income-01]: R10,000, 2026-11-27, 10 dae.";
        Map<String, String> terms = Map.of("notice", "kennisgewing");
        assertThatCode(() -> CommunicationGuard.translation(english, translated, terms)).doesNotThrowAnyException();
        assertThatThrownBy(() -> CommunicationGuard.translation(english, translated.replace("income", "income-alt"), terms))
                .isInstanceOf(AiOutputInvalidException.class);
        assertThatThrownBy(() -> CommunicationGuard.translation(english, translated.replace("Kennisgewing", "Brief"), terms))
                .isInstanceOf(AiOutputInvalidException.class);
        assertThatThrownBy(() -> CommunicationGuard.translation(english, translated.replace("2026-11-27", "2026-11-28"), terms))
                .isInstanceOf(AiOutputInvalidException.class);
        assertThatThrownBy(() -> CommunicationGuard.translation(english, translated + " 10", terms))
                .isInstanceOf(AiOutputInvalidException.class);
    }

    @Test
    void requiresNeutralWording() {
        assertThatCode(() -> CommunicationGuard.neutral("The two recorded amounts differ. Please help us check them."))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> CommunicationGuard.neutral("The applicant lied.")).isInstanceOf(AiOutputInvalidException.class);
    }
}
