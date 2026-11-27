const contentEditor = document.querySelector("#draft-content");
const ruleField = document.querySelector("#rule-field");
const ruleOperator = document.querySelector("#rule-operator");
const ruleValueWrap = document.querySelector("#rule-value-wrap");
const ruleBuilderError = document.querySelector("#rule-builder-error");
const ruleCountMessage = document.querySelector("#policy-rule-count");
const reviewForm = document.querySelector("#submit-policy-review");

if (contentEditor && ruleField && ruleOperator && ruleValueWrap) {
    const operatorLabels = {
        GREATER_THAN_OR_EQUAL: "At least",
        LESS_THAN_OR_EQUAL: "At most",
        EQUALS: "Equals",
        NOT_EQUALS: "Does not equal",
        IS_TRUE: "Must be true"
    };

    const documentFromEditor = () => {
        const parsed = JSON.parse(contentEditor.value);
        if (parsed.schemaVersion !== 1 || !Array.isArray(parsed.rules)) {
            throw new Error("The policy content must have schemaVersion 1 and a rules list.");
        }
        return parsed;
    };

    const ruleCodes = (rules, codes = new Set()) => {
        rules.forEach((rule) => {
            if (rule.ruleCode) codes.add(rule.ruleCode.toLowerCase());
            if (rule.group && Array.isArray(rule.group.rules)) ruleCodes(rule.group.rules, codes);
        });
        return codes;
    };

    const showError = (message) => {
        ruleBuilderError.textContent = message;
        ruleBuilderError.hidden = false;
        ruleBuilderError.focus();
    };

    const clearError = () => {
        ruleBuilderError.textContent = "";
        ruleBuilderError.hidden = true;
    };

    const updateCount = (count) => {
        if (ruleCountMessage) {
            ruleCountMessage.textContent = count === 0
                ? "This draft has no rules yet. Add at least one rule before submitting it for review."
                : `${count} top-level rule${count === 1 ? "" : "s"} in the editor. Save draft changes to keep these edits.`;
            ruleCountMessage.classList.toggle("alert-warning", count === 0);
            ruleCountMessage.classList.toggle("alert-success", count > 0);
        }
        if (reviewForm) reviewForm.dataset.ruleCount = String(count);
    };

    const replaceValueControl = (type, unit) => {
        const previous = document.querySelector("#rule-value");
        const label = ruleValueWrap.querySelector("label");
        const next = type === "BOOLEAN" ? document.createElement("select") : document.createElement("input");
        next.id = "rule-value";
        next.className = "form-control";
        next.required = true;
        if (type === "BOOLEAN") {
            next.innerHTML = '<option value="true">Yes</option>';
        } else if (["MONEY", "DECIMAL", "INTEGER"].includes(type)) {
            next.type = "number";
            next.step = type === "INTEGER" ? "1" : "any";
        } else if (type === "DATE") {
            next.type = "date";
        } else {
            next.type = "text";
            next.maxLength = 1000;
        }
        previous.replaceWith(next);
        label.textContent = unit ? `Required value (${unit})` : "Required value";
    };

    const populateOperators = () => {
        const selected = ruleField.selectedOptions[0];
        const type = selected?.dataset.valueType;
        ruleOperator.replaceChildren();
        const placeholder = document.createElement("option");
        placeholder.value = "";
        placeholder.textContent = type ? "Choose a requirement" : "Choose a field first";
        ruleOperator.append(placeholder);
        if (!type) {
            ruleOperator.disabled = true;
            return;
        }
        const operators = type === "BOOLEAN" ? ["IS_TRUE"]
            : ["MONEY", "DECIMAL", "INTEGER"].includes(type)
                ? ["GREATER_THAN_OR_EQUAL", "LESS_THAN_OR_EQUAL", "EQUALS", "NOT_EQUALS"]
                : ["EQUALS", "NOT_EQUALS"];
        operators.forEach((value) => {
            const option = document.createElement("option");
            option.value = value;
            option.textContent = operatorLabels[value];
            ruleOperator.append(option);
        });
        ruleOperator.disabled = false;
        replaceValueControl(type, selected.dataset.unit || "");
    };

    const updateNextRuleCode = () => {
        try {
            const codes = ruleCodes(documentFromEditor().rules);
            let suffix = 1;
            while (codes.has(`rule_${suffix}`)) suffix += 1;
            document.querySelector("#rule-code").value = `RULE_${suffix}`;
        } catch { /* Keep the existing code until the advanced JSON is valid. */ }
    };

    ruleField.addEventListener("change", populateOperators);
    document.querySelector("#add-policy-rule")?.addEventListener("click", () => {
        clearError();
        try {
            const policy = documentFromEditor();
            const selectedField = ruleField.selectedOptions[0];
            const code = document.querySelector("#rule-code").value.trim();
            const name = document.querySelector("#rule-name").value.trim();
            const operator = ruleOperator.value;
            const rawValue = document.querySelector("#rule-value").value;
            const mandatoryValue = document.querySelector("#rule-mandatory").value;
            const onMissingEvidence = document.querySelector("#rule-missing").value;
            const sourceDocument = document.querySelector("#rule-source-document").value.trim();
            const sourceSection = document.querySelector("#rule-source-section").value.trim();
            const sourceQuote = document.querySelector("#rule-source-quote").value.trim();

            if (!selectedField?.value) throw new Error("Choose a field from the rule catalogue.");
            if (!/^[A-Za-z][A-Za-z0-9_-]{0,99}$/.test(code)) throw new Error("Use a rule code starting with a letter, with up to 100 letters, numbers, underscores or hyphens.");
            if (ruleCodes(policy.rules).has(code.toLowerCase())) throw new Error("That rule code is already in use. Choose a unique code.");
            if (!name || name.length > 255) throw new Error("Enter a rule name of up to 255 characters.");
            if (!operator) throw new Error("Choose how the rule should check the field.");
            if (mandatoryValue === "") throw new Error("Choose whether a failed rule affects eligibility.");
            if (!onMissingEvidence) throw new Error("Choose what should happen when evidence is missing.");
            if (operator !== "IS_TRUE" && rawValue === "") throw new Error("Enter the value this rule should check.");
            if (!sourceDocument || sourceDocument.length > 255 || !sourceSection || sourceSection.length > 255
                    || !sourceQuote || sourceQuote.length > 4000) {
                throw new Error("Add a source document, section and exact wording (up to 4,000 characters) so this rule can be reviewed.");
            }
            const pageText = document.querySelector("#rule-source-page").value.trim();
            if (pageText && (!Number.isInteger(Number(pageText)) || Number(pageText) < 1)) {
                throw new Error("Enter a whole page number greater than zero, or leave it blank.");
            }

            const type = selectedField.dataset.valueType;
            let expectedValue = rawValue;
            if (["MONEY", "DECIMAL", "INTEGER"].includes(type)) {
                expectedValue = Number(rawValue);
                if (!Number.isFinite(expectedValue) || (type === "INTEGER" && !Number.isInteger(expectedValue))) {
                    throw new Error("Enter a valid number for the selected field.");
                }
            } else if (type === "BOOLEAN") {
                expectedValue = true;
            }
            const requiredEvidence = Array.from(document.querySelector("#rule-evidence").selectedOptions)
                .map((option) => option.value);

            policy.rules.push({
                ruleCode: code,
                name,
                fieldKey: selectedField.value,
                operator,
                expectedValue,
                unit: selectedField.dataset.unit || null,
                mandatory: mandatoryValue === "true",
                requiredEvidence,
                onMissingEvidence,
                source: {
                    document: sourceDocument,
                    section: sourceSection,
                    page: pageText ? Number(pageText) : null,
                    quote: sourceQuote
                },
                group: null,
                condition: null
            });
            contentEditor.value = JSON.stringify(policy, null, 2);
            updateCount(policy.rules.length);
            updateNextRuleCode();
            document.querySelector("#rule-name").value = "";
            document.querySelector("#rule-source-quote").value = "";
            document.querySelector("#rule-source-page").value = "";
            document.querySelector("#rule-name").focus();
        } catch (error) {
            showError(error instanceof SyntaxError
                ? "The advanced JSON has a formatting error. Correct it below before adding a guided rule."
                : error.message);
        }
    });

    contentEditor.addEventListener("input", () => {
        try {
            const policy = documentFromEditor();
            updateCount(policy.rules.length);
            updateNextRuleCode();
        } catch {
            if (ruleCountMessage) {
                ruleCountMessage.textContent = "The advanced JSON needs correction before it can be saved.";
                ruleCountMessage.classList.add("alert-warning");
                ruleCountMessage.classList.remove("alert-success");
            }
        }
    });

    contentEditor.form?.addEventListener("submit", (event) => {
        try {
            documentFromEditor();
        } catch {
            event.preventDefault();
            showError("Correct the policy JSON before saving. Open the advanced editor and check the formatting.");
            document.querySelector(".advanced-rule-editor")?.setAttribute("open", "");
            contentEditor.focus();
        }
    });

    reviewForm?.addEventListener("submit", (event) => {
        try {
            const policy = documentFromEditor();
            if (policy.rules.length > 0) return;
            event.preventDefault();
            showError("Add at least one rule to the policy content before submitting it for review.");
            document.querySelector("#rule-name")?.focus();
        } catch {
            event.preventDefault();
            showError("Correct the policy JSON before submitting this draft for review.");
            document.querySelector("#draft-content")?.focus();
        }
    });

    updateNextRuleCode();
}
