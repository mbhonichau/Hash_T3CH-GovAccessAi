package za.gov.govaccess.policy.service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import za.gov.govaccess.ai.dto.AiCallContext;
import za.gov.govaccess.ai.provider.AiProviderException;
import za.gov.govaccess.ai.service.AiOutputInvalidException;
import za.gov.govaccess.ai.service.AiReplayMissException;
import za.gov.govaccess.ai.service.PolicyInterpretationService;
import za.gov.govaccess.document.service.PolicySourceReader;
import za.gov.govaccess.policy.repository.PolicyImportRepository;

@Component
public class PolicyImportWorker {
    private final PolicyImportRepository jobs;
    private final PolicySourceReader sources;
    private final PolicyInterpretationService interpreter;
    private final PolicyImportJobs drafts;

    public PolicyImportWorker(PolicyImportRepository jobs, PolicySourceReader sources,
                              PolicyInterpretationService interpreter, PolicyImportJobs drafts) {
        this.jobs = jobs;
        this.sources = sources;
        this.interpreter = interpreter;
        this.drafts = drafts;
    }

    @Scheduled(fixedDelayString = "${govaccess.policy-import.poll-ms:1500}",
               initialDelayString = "${govaccess.policy-import.poll-ms:1500}")
    public void processNext() {
        jobs.expireRunningBefore(Instant.now().minus(Duration.ofHours(1)));
        var next = jobs.queued();
        if (next.isEmpty() || !jobs.claim(next.get().id())) {
            return;
        }
        var job = next.get();
        try {
            var source = jobs.source(job.id());
            var chunks = sources.read(source.text(), source.pdf());
            jobs.progress(job.id(), 0, chunks.size());
            var context = new AiCallContext(job.userId(), "POLICY_VERSION", job.draftId());
            var review = interpreter.interpretDocument(job.sourceLabel(), chunks, context,
                    count -> jobs.progress(job.id(), count, chunks.size()));
            drafts.complete(job, review);
        } catch (IOException e) {
            jobs.fail(job.id(), "The PDF could not be read. Upload a readable text PDF.");
        } catch (AiProviderException | AiReplayMissException e) {
            jobs.fail(job.id(), "Document imports require an enabled, locally processed AI provider.");
        } catch (AiOutputInvalidException e) {
            jobs.fail(job.id(), "The AI response failed document rule validation.");
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            jobs.fail(job.id(), message != null && message.length() <= 255 ? message : "The source or draft is invalid.");
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(PolicyImportWorker.class).warn("Policy import {} failed ({})",
                    job.id(), e.getClass().getSimpleName());
            jobs.fail(job.id(), "The draft could not be saved. Check its label and try again.");
        }
    }
}
