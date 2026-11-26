const policyImport = document.querySelector("#policy-ai-import");

policyImport?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const submit = policyImport.querySelector("[data-ai-submit]");
    const error = policyImport.querySelector("[data-ai-error]");
    const progress = policyImport.querySelector("[data-ai-progress]");
    if (submit.disabled) {
        return;
    }
    submit.disabled = true;
    submit.textContent = "Importing…";
    error.hidden = true;
    progress.textContent = "Waiting for the AI provider to return a draft.";
    progress.hidden = false;
    const reminder = window.setTimeout(() => {
        progress.textContent = "The provider is still processing the import. Keep this page open while it finishes.";
    }, 10000);
    let openingDraft = false;
    try {
        const response = await fetch(policyImport.action, {
            method: "POST",
            body: new URLSearchParams(new FormData(policyImport)),
            headers: {Accept: "application/json"}
        });
        if (response.redirected) {
            throw new Error("Your session may have expired. Sign in again, switch to Policy Edit, and retry.");
        }
        const payload = await response.json().catch(() => ({}));
        if (response.status === 403) {
            throw new Error("Import requires an active Policy Edit session. Reload the page or switch to Policy Edit and retry.");
        }
        if (!response.ok || !payload.location) {
            throw new Error(payload.message ?? "The server did not return a draft. Try again or check the application log.");
        }
        progress.textContent = "Draft saved. Opening it for review…";
        window.location.assign(payload.location);
        openingDraft = true;
    } catch (failure) {
        error.textContent = failure.message;
        error.hidden = false;
    } finally {
        window.clearTimeout(reminder);
        if (!openingDraft) {
            progress.hidden = true;
            submit.disabled = false;
            submit.textContent = "Import with AI";
        }
    }
});
