package za.gov.govaccess.document;

import org.junit.jupiter.api.Test;
import za.gov.govaccess.document.service.PolicySourceReader;

import static org.assertj.core.api.Assertions.assertThat;

class PolicySourceReaderTests {
    @Test
    void acceptsPoliciesWithMoreSectionsThanTheOldHundredChunkLimit() throws Exception {
        var text = new StringBuilder();
        for (int section = 1; section <= 150; section++) {
            text.append("SECTION ").append(section).append('\n')
                    .append("Applicants must meet requirement ").append(section).append(".\n");
        }

        var chunks = new PolicySourceReader(12_000, 500).read(text.toString(), null);

        assertThat(chunks).hasSize(150);
        assertThat(chunks.get(149).text()).contains("Applicants must meet requirement 150.");
    }

    @Test
    void uppercaseProseDoesNotCreateASeparateChunkForEveryLine() throws Exception {
        String text = ("ELIGIBILITY REQUIREMENTS\nApplicants must be enrolled.\n").repeat(150);

        var chunks = new PolicySourceReader(12_000, 500).read(text, null);

        assertThat(chunks).hasSize(1);
    }
}
