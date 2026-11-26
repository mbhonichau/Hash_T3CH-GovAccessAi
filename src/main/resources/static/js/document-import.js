const importStatus = document.querySelector("[data-poll-url]");

async function pollImport() {
    try {
        const response = await fetch(importStatus.dataset.pollUrl, {headers: {Accept: "application/json"}});
        if (!response.ok || response.redirected) {
            throw new Error("Status is unavailable.");
        }
        const job = await response.json();
        if (job.status === "SUCCEEDED" || job.status === "FAILED") {
            window.location.reload();
            return;
        }
        importStatus.textContent = job.totalChunks
            ? `${job.status} · ${job.completedChunks}/${job.totalChunks} sections processed`
            : `${job.status} · Waiting for the import worker`;
    } catch {
        importStatus.textContent = "Could not refresh the import status. Retrying…";
    }
    window.setTimeout(pollImport, 2500);
}

if (importStatus) {
    window.setTimeout(pollImport, 1000);
}
