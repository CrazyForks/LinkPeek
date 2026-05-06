(function () {
    const state = {
        prompts: [],
        aiProviders: [],
        aiProviderDowngradeConfig: {},
        defaultTitleFormatPrompt: "",
        aiProviderDowngradeSaveTimer: null,
        aiProviderDowngradeSaveVersion: 0,
        logRefreshTimer: null
    };

    function init() {
        bindLogout();
        bindPurge();
        bindPromptForm();
        bindAiTitleConfig();
        bindProviderForm();
        bindAiProviderDowngradeConfig();
        bindAiForm();
        bindLogs();
        bindModalClose();
        checkSession();
    }

    async function checkSession() {
        const session = await fetchJson("/api/admin/session", {method: "GET"}, false);
        if (!session || !session.enabled) {
            redirectToLogin();
            return;
        }
        if (session.authenticated) {
            showAdmin();
            await loadAll();
            return;
        }
        redirectToLogin();
    }

    function bindLogout() {
        document.getElementById("logout-button").addEventListener("click", async () => {
            await fetchJson("/api/admin/logout", {method: "POST"}, false);
            window.location.href = "/admin/login";
        });
    }

    function bindPurge() {
        document.getElementById("purge-button").addEventListener("click", async () => {
            if (!window.confirm("确认清理全部统计数据？")) {
                return;
            }
            setFeedback("purge-feedback", "正在清理...", "");
            try {
                const result = await fetchJson("/api/admin/stats/purge-all", {method: "POST"});
                setFeedback("purge-feedback", `已清理 ${result.deletedEvents || 0} 条事件，${result.deletedLinks || 0} 条链接。`, "is-success");
            } catch (error) {
                setFeedback("purge-feedback", error.message, "is-error");
            }
        });
    }

    function bindPromptForm() {
        document.getElementById("prompt-new-button").addEventListener("click", openPromptModalForCreate);
        document.getElementById("prompt-cancel-button").addEventListener("click", closePromptModal);
        document.getElementById("prompt-form").addEventListener("submit", async (event) => {
            event.preventDefault();
            const style = document.getElementById("prompt-style").value.trim();
            const prompt = document.getElementById("prompt-text").value.trim();
            setFeedback("prompt-modal-feedback", "正在保存 Style Prompt...", "");
            try {
                await fetchJson(`/api/admin/prompts/${encodeURIComponent(style)}`, {
                    method: "PUT",
                    body: JSON.stringify({prompt})
                });
                closePromptModal(false);
                await loadPrompts();
                setFeedback("prompt-feedback", "Style Prompt 已保存。", "is-success");
            } catch (error) {
                setFeedback("prompt-modal-feedback", error.message, "is-error");
            }
        });
    }

    function bindAiTitleConfig() {
        document.getElementById("ai-title-config-form").addEventListener("submit", async (event) => {
            event.preventDefault();
            const titleFormatPrompt = document.getElementById("ai-title-format-prompt").value.trim();
            setFeedback("ai-title-config-feedback", "正在保存标题格式...", "");
            try {
                await fetchJson("/api/admin/ai-title-config", {
                    method: "PUT",
                    body: JSON.stringify({titleFormatPrompt})
                });
                await loadAiTitleConfig();
                setFeedback("ai-title-config-feedback", "标题格式已保存。", "is-success");
            } catch (error) {
                setFeedback("ai-title-config-feedback", error.message, "is-error");
            }
        });
        document.getElementById("ai-title-format-default-button").addEventListener("click", () => {
            document.getElementById("ai-title-format-prompt").value = state.defaultTitleFormatPrompt || "";
            setFeedback("ai-title-config-feedback", "已填入默认标题格式，保存后生效。", "");
        });
    }


    function bindProviderForm() {
        document.getElementById("provider-form").addEventListener("submit", async (event) => {
            event.preventDefault();
            const valuesByProvider = {};
            document.querySelectorAll("[data-provider][data-key]").forEach((input) => {
                valuesByProvider[input.dataset.provider] ||= {};
                valuesByProvider[input.dataset.provider][input.dataset.key] = input.value.trim();
            });
            setFeedback("provider-feedback", "正在保存 Provider 配置...", "");
            try {
                await Promise.all(Object.entries(valuesByProvider).map(([providerId, values]) => {
                    return fetchJson(`/api/admin/provider-config/${encodeURIComponent(providerId)}`, {
                        method: "PUT",
                        body: JSON.stringify({values})
                    });
                }));
                await loadProviderConfig();
                setFeedback("provider-feedback", "Provider 配置已保存。", "is-success");
            } catch (error) {
                setFeedback("provider-feedback", error.message, "is-error");
            }
        });
    }

    function bindAiProviderDowngradeConfig() {
        const form = document.getElementById("ai-downgrade-config-form");
        const enabledToggle = document.getElementById("ai-auto-downgrade-enabled-toggle");
        const thresholdInput = document.getElementById("ai-auto-downgrade-timeout-threshold");
        form.addEventListener("submit", (event) => {
            event.preventDefault();
            scheduleAiProviderDowngradeSave();
        });
        enabledToggle.addEventListener("click", () => {
            setAiProviderDowngradeEnabled(enabledToggle.dataset.enabled !== "true");
            scheduleAiProviderDowngradeSave();
        });
        thresholdInput.addEventListener("input", scheduleAiProviderDowngradeSave);
    }

    function bindAiForm() {
        document.getElementById("ai-new-button").addEventListener("click", openAiFormForCreate);
        document.getElementById("ai-cancel-button").addEventListener("click", closeAiModal);
        document.getElementById("ai-form").addEventListener("submit", async (event) => {
            event.preventDefault();
            const id = document.getElementById("ai-id").value;
            const requestTimeoutSeconds = Number(document.getElementById("ai-request-timeout-seconds").value || 0);
            const payload = {
                name: document.getElementById("ai-name").value.trim(),
                baseUrl: document.getElementById("ai-base-url").value.trim(),
                apiKind: document.getElementById("ai-api-kind").value,
                model: document.getElementById("ai-model").value.trim(),
                effort: document.getElementById("ai-effort").value.trim(),
                requestTimeoutSeconds,
                apiKey: document.getElementById("ai-api-key").value.trim()
            };
            if (!Number.isInteger(requestTimeoutSeconds) || requestTimeoutSeconds < 1 || requestTimeoutSeconds > 600) {
                setFeedback("ai-modal-feedback", "AI 请求超时必须是 1-600 秒之间的整数。", "is-error");
                document.getElementById("ai-request-timeout-seconds").focus();
                return;
            }
            const baseUrlError = validateAiBaseUrl(payload.baseUrl);
            if (baseUrlError) {
                setFeedback("ai-modal-feedback", baseUrlError, "is-error");
                document.getElementById("ai-base-url").focus();
                return;
            }
            const url = id ? `/api/admin/ai-providers/${encodeURIComponent(id)}` : "/api/admin/ai-providers";
            const method = id ? "PUT" : "POST";
            setFeedback("ai-modal-feedback", "正在保存 AI Provider...", "");
            try {
                await fetchJson(url, {
                    method,
                    body: JSON.stringify(payload)
                });
                closeAiModal(false);
                await loadAiProviders();
                setFeedback("ai-feedback", "AI Provider 已保存。", "is-success");
            } catch (error) {
                setFeedback("ai-modal-feedback", error.message, "is-error");
            }
        });
    }

    function bindLogs() {
        document.getElementById("log-form").addEventListener("submit", async (event) => {
            event.preventDefault();
            await loadLogs();
        });
        document.getElementById("log-auto-refresh").addEventListener("change", updateLogAutoRefresh);
    }

    function bindModalClose() {
        document.querySelectorAll("[data-close-modal]").forEach((node) => {
            node.addEventListener("click", () => {
                if (node.dataset.closeModal === "prompt") {
                    closePromptModal();
                    return;
                }
                if (node.dataset.closeModal === "ai") {
                    closeAiModal();
                }
            });
        });
        document.addEventListener("keydown", (event) => {
            if (event.key !== "Escape") {
                return;
            }
            if (!document.getElementById("prompt-modal").hidden) {
                closePromptModal();
                return;
            }
            if (!document.getElementById("ai-modal").hidden) {
                closeAiModal();
            }
        });
    }

    async function loadAll() {
        await Promise.all([
            loadPrompts(),
            loadAiTitleConfig(),
            loadProviderConfig(),
            loadAiProviderDowngradeConfig(),
            loadAiProviders(),
            loadLogs()
        ]);
    }

    async function loadPrompts() {
        state.prompts = await fetchJson("/api/admin/prompts");
        renderPrompts();
    }

    async function loadAiTitleConfig() {
        const payload = await fetchJson("/api/admin/ai-title-config");
        state.defaultTitleFormatPrompt = payload.defaultTitleFormatPrompt || "";
        document.getElementById("ai-title-format-prompt").value = payload.titleFormatPrompt || "";
    }

    async function loadProviderConfig() {
        const payload = await fetchJson("/api/admin/provider-config");
        const configs = payload.configs || {};
        document.querySelectorAll("[data-provider][data-key]").forEach((input) => {
            input.value = (configs[input.dataset.provider] || {})[input.dataset.key] || "";
        });
    }

    async function loadAiProviders() {
        state.aiProviders = await fetchJson("/api/admin/ai-providers");
        renderAiProviders();
    }

    async function loadAiProviderDowngradeConfig() {
        state.aiProviderDowngradeConfig = await fetchJson("/api/admin/ai-provider-downgrade-config");
        renderAiProviderDowngradeConfig();
    }

    function scheduleAiProviderDowngradeSave() {
        state.aiProviderDowngradeSaveVersion += 1;
        const saveVersion = state.aiProviderDowngradeSaveVersion;
        if (state.aiProviderDowngradeSaveTimer) {
            window.clearTimeout(state.aiProviderDowngradeSaveTimer);
            state.aiProviderDowngradeSaveTimer = null;
        }

        const payload = readAiProviderDowngradePayload();
        if (!payload) {
            setFeedback("ai-downgrade-config-feedback", "自动降级超时次数必须是 1-100 之间的整数。", "is-error");
            return;
        }

        setFeedback("ai-downgrade-config-feedback", "将在 2 秒后自动保存...", "");
        state.aiProviderDowngradeSaveTimer = window.setTimeout(() => {
            state.aiProviderDowngradeSaveTimer = null;
            saveAiProviderDowngradeConfig(payload, saveVersion);
        }, 2000);
    }

    function readAiProviderDowngradePayload() {
        const autoDowngradeEnabled = document.getElementById("ai-auto-downgrade-enabled-toggle").dataset.enabled === "true";
        const autoDowngradeTimeoutThreshold = Number(document.getElementById("ai-auto-downgrade-timeout-threshold").value || 0);
        if (!Number.isInteger(autoDowngradeTimeoutThreshold) || autoDowngradeTimeoutThreshold < 1 || autoDowngradeTimeoutThreshold > 100) {
            return null;
        }
        return {autoDowngradeEnabled, autoDowngradeTimeoutThreshold};
    }

    async function saveAiProviderDowngradeConfig(payload, saveVersion) {
        setFeedback("ai-downgrade-config-feedback", "正在保存自动降级配置...", "");
        try {
            const response = await fetchJson("/api/admin/ai-provider-downgrade-config", {
                method: "PUT",
                body: JSON.stringify(payload)
            });
            if (saveVersion !== state.aiProviderDowngradeSaveVersion) {
                return;
            }
            state.aiProviderDowngradeConfig = response;
            renderAiProviderDowngradeConfig();
            setFeedback("ai-downgrade-config-feedback", "自动降级配置已保存。", "is-success");
        } catch (error) {
            if (saveVersion !== state.aiProviderDowngradeSaveVersion) {
                return;
            }
            setFeedback("ai-downgrade-config-feedback", error.message, "is-error");
        }
    }

    async function loadLogs() {
        const params = new URLSearchParams();
        params.set("lines", document.getElementById("log-lines").value || "300");
        const level = document.getElementById("log-level").value;
        const query = document.getElementById("log-query").value.trim();
        if (level) {
            params.set("level", level);
        }
        if (query) {
            params.set("q", query);
        }

        setFeedback("log-feedback", "正在读取服务日志...", "");
        try {
            const result = await fetchJson(`/api/admin/logs?${params.toString()}`);
            renderLogs(result);
        } catch (error) {
            setFeedback("log-feedback", error.message, "is-error");
        }
    }

    function renderPrompts() {
        const body = document.getElementById("prompt-table");
        if (!state.prompts.length) {
            body.innerHTML = `<tr><td colspan="3" class="muted">暂无 Style Prompt</td></tr>`;
            return;
        }
        body.innerHTML = state.prompts.map((prompt) => `
            <tr>
                <td>${escapeHtml(prompt.style)}</td>
                <td><div class="prompt-preview">${escapeHtml(promptPreview(prompt.prompt))}</div></td>
                <td>
                    <div class="row-actions">
                        <button type="button" data-edit-prompt="${escapeHtml(prompt.style)}" class="secondary">编辑</button>
                        <button type="button" data-delete-prompt="${escapeHtml(prompt.style)}" class="danger">删除</button>
                    </div>
                </td>
            </tr>
        `).join("");
        body.querySelectorAll("[data-edit-prompt]").forEach((button) => {
            button.addEventListener("click", () => {
                const prompt = state.prompts.find((item) => item.style === button.dataset.editPrompt);
                if (prompt) {
                    openPromptModalForEdit(prompt);
                }
            });
        });
        body.querySelectorAll("[data-delete-prompt]").forEach((button) => {
            button.addEventListener("click", async () => {
                await fetchJson(`/api/admin/prompts/${encodeURIComponent(button.dataset.deletePrompt)}`, {method: "DELETE"});
                await loadPrompts();
            });
        });
    }

    function renderAiProviders() {
        const body = document.getElementById("ai-table");
        if (!state.aiProviders.length) {
            body.innerHTML = `<tr><td colspan="8" class="muted">暂无 AI Provider</td></tr>`;
            return;
        }
        body.innerHTML = state.aiProviders.map((provider) => `
            <tr data-ai-row="${provider.id}">
                <td class="drag-cell">
                    <button type="button" class="drag-handle" data-drag-ai="${provider.id}" draggable="true" title="拖拽排序" aria-label="拖拽排序">↕</button>
                </td>
                <td>${escapeHtml(provider.name)}</td>
                <td>${escapeHtml(provider.baseUrl)}</td>
                <td>${escapeHtml(aiKindLabel(providerApiKind(provider)))}</td>
                <td>${escapeHtml(provider.model)}</td>
                <td>${providerRequestTimeoutLabel(provider)}</td>
                <td>
                    <label class="switch-row">
                        <input type="checkbox" data-toggle-ai="${provider.id}" ${provider.enabled ? "checked" : ""}>
                        <span class="switch-track" aria-hidden="true"><span class="switch-thumb"></span></span>
                        <span class="switch-text">${provider.enabled ? "启用" : "禁用"}</span>
                    </label>
                </td>
                <td>
                    <div class="row-actions">
                        <button type="button" data-test-ai="${provider.id}" class="secondary test-button">测试</button>
                        <button type="button" data-edit-ai="${provider.id}" class="secondary">编辑</button>
                        <button type="button" data-delete-ai="${provider.id}" class="danger">删除</button>
                    </div>
                </td>
            </tr>
        `).join("");
        bindAiDragSorting(body);
        body.querySelectorAll("[data-toggle-ai]").forEach((input) => {
            input.addEventListener("change", () => toggleAiProvider(input));
        });
        body.querySelectorAll("[data-test-ai]").forEach((button) => {
            button.addEventListener("click", () => testAiProvider(button));
        });
        body.querySelectorAll("[data-edit-ai]").forEach((button) => {
            button.addEventListener("click", () => openAiFormForEdit(Number(button.dataset.editAi)));
        });
        body.querySelectorAll("[data-delete-ai]").forEach((button) => {
            button.addEventListener("click", async () => {
                await fetchJson(`/api/admin/ai-providers/${encodeURIComponent(button.dataset.deleteAi)}`, {method: "DELETE"});
                if (document.getElementById("ai-id").value === button.dataset.deleteAi) {
                    closeAiModal(false);
                }
                await loadAiProviders();
            });
        });
    }

    function renderAiProviderDowngradeConfig() {
        const config = state.aiProviderDowngradeConfig || {};
        setAiProviderDowngradeEnabled(Boolean(config.autoDowngradeEnabled));
        document.getElementById("ai-auto-downgrade-timeout-threshold").value = config.autoDowngradeTimeoutThreshold || 3;
    }

    function setAiProviderDowngradeEnabled(enabled) {
        const toggle = document.getElementById("ai-auto-downgrade-enabled-toggle");
        const label = document.getElementById("ai-auto-downgrade-enabled-label");
        toggle.dataset.enabled = enabled ? "true" : "false";
        toggle.setAttribute("aria-pressed", enabled ? "true" : "false");
        toggle.classList.toggle("is-enabled", enabled);
        label.textContent = enabled ? "启用" : "禁用";
    }

    function bindAiDragSorting(body) {
        body.ondragover = (event) => {
            const draggingRow = body.querySelector("tr.is-dragging");
            if (!draggingRow) {
                return;
            }
            event.preventDefault();
            const afterRow = dragAfterRow(body, event.clientY);
            if (afterRow) {
                body.insertBefore(draggingRow, afterRow);
                return;
            }
            body.appendChild(draggingRow);
        };
        body.ondrop = async (event) => {
            event.preventDefault();
            const ids = Array.from(body.querySelectorAll("[data-ai-row]"))
                    .map((row) => Number(row.dataset.aiRow));
            await saveAiProviderOrder(ids);
        };
        body.querySelectorAll("[data-drag-ai]").forEach((handle) => {
            handle.addEventListener("dragstart", (event) => {
                const row = handle.closest("[data-ai-row]");
                if (!row) {
                    return;
                }
                row.classList.add("is-dragging");
                event.dataTransfer.effectAllowed = "move";
                event.dataTransfer.setData("text/plain", handle.dataset.dragAi);
            });
            handle.addEventListener("dragend", () => {
                body.querySelectorAll(".is-dragging").forEach((row) => row.classList.remove("is-dragging"));
            });
        });
    }

    function dragAfterRow(body, y) {
        return Array.from(body.querySelectorAll("tr[data-ai-row]:not(.is-dragging)"))
                .reduce((closest, row) => {
                    const box = row.getBoundingClientRect();
                    const offset = y - box.top - box.height / 2;
                    if (offset < 0 && offset > closest.offset) {
                        return {offset, row};
                    }
                    return closest;
                }, {offset: Number.NEGATIVE_INFINITY, row: null}).row;
    }

    async function saveAiProviderOrder(ids) {
        const currentIds = state.aiProviders.map((provider) => Number(provider.id));
        if (ids.length === currentIds.length && ids.every((id, index) => id === currentIds[index])) {
            return;
        }
        setFeedback("ai-feedback", "正在保存 AI Provider 排序...", "");
        try {
            state.aiProviders = await fetchJson("/api/admin/ai-providers/reorder", {
                method: "PUT",
                body: JSON.stringify({ids})
            });
            renderAiProviders();
            setFeedback("ai-feedback", "AI Provider 排序已保存。", "is-success");
        } catch (error) {
            await loadAiProviders();
            setFeedback("ai-feedback", error.message, "is-error");
        }
    }

    async function toggleAiProvider(input) {
        const providerId = input.dataset.toggleAi;
        const enabled = input.checked;
        input.disabled = true;
        setFeedback("ai-feedback", "正在更新 AI Provider 状态...", "");
        try {
            const updated = await fetchJson(`/api/admin/ai-providers/${encodeURIComponent(providerId)}/enabled`, {
                method: "PUT",
                body: JSON.stringify({enabled})
            });
            state.aiProviders = state.aiProviders.map((provider) => {
                return Number(provider.id) === Number(updated.id) ? updated : provider;
            });
            renderAiProviders();
            setFeedback("ai-feedback", `${updated.name || "AI Provider"} 已${updated.enabled ? "启用" : "禁用"}。`, "is-success");
        } catch (error) {
            input.checked = !enabled;
            setFeedback("ai-feedback", error.message, "is-error");
        } finally {
            input.disabled = false;
        }
    }

    async function testAiProvider(button) {
        const providerId = button.dataset.testAi;
        button.disabled = true;
        button.classList.remove("is-success", "is-error");
        button.textContent = "测试中";
        button.removeAttribute("title");
        setFeedback("ai-feedback", "正在测试 AI Provider...", "");
        try {
            const result = await fetchJson(`/api/admin/ai-providers/${encodeURIComponent(providerId)}/test`, {
                method: "POST"
            });
            button.classList.toggle("is-success", Boolean(result.success));
            button.classList.toggle("is-error", !result.success);
            button.textContent = result.success ? "成功" : "失败";
            button.title = result.message || "";
            const message = result.message || (result.success ? "测试成功。" : "测试失败。");
            const details = [];
            if (typeof result.durationMs === "number") {
                details.push(`耗时 ${result.durationMs}ms`);
            }
            if (result.output) {
                details.push(`返回：${result.output}`);
            }
            const feedback = details.length > 0
                    ? `${trimTrailingPunctuation(message)}，${details.join("，")}`
                    : message;
            setFeedback("ai-feedback", feedback, result.success ? "is-success" : "is-error");
        } catch (error) {
            button.classList.add("is-error");
            button.textContent = "失败";
            button.title = error.message;
            setFeedback("ai-feedback", error.message, "is-error");
        } finally {
            button.disabled = false;
        }
    }

    function renderLogs(result) {
        const output = document.getElementById("log-output");
        const meta = document.getElementById("log-meta");
        const lines = Array.isArray(result.lines) ? result.lines : [];
        meta.textContent = `路径：${result.path || ""} · 大小：${formatBytes(result.sizeBytes || 0)} · 更新：${formatTimestamp(result.modifiedAt)}`;

        if (!result.exists) {
            output.textContent = "";
            setFeedback("log-feedback", "日志文件不存在或不可读。", "is-error");
            return;
        }

        output.textContent = lines.length ? lines.join("\n") : "没有匹配日志。";
        output.scrollTop = output.scrollHeight;
        const truncated = result.truncated ? "，已截断" : "";
        setFeedback("log-feedback", `已加载 ${lines.length} 行${truncated}。`, "is-success");
    }

    function updateLogAutoRefresh() {
        if (state.logRefreshTimer) {
            window.clearInterval(state.logRefreshTimer);
            state.logRefreshTimer = null;
        }
        if (document.getElementById("log-auto-refresh").checked) {
            state.logRefreshTimer = window.setInterval(loadLogs, 5000);
        }
    }

    function openPromptModalForCreate() {
        resetPromptForm();
        document.getElementById("prompt-modal-title").textContent = "新建 Style Prompt";
        document.getElementById("prompt-style").disabled = false;
        openModal("prompt-modal");
        setFeedback("prompt-modal-feedback", "", "");
        document.getElementById("prompt-style").focus();
    }

    function openPromptModalForEdit(prompt) {
        resetPromptForm();
        document.getElementById("prompt-modal-title").textContent = `编辑 Style Prompt：${prompt.style}`;
        document.getElementById("prompt-style").value = prompt.style;
        document.getElementById("prompt-style").disabled = true;
        document.getElementById("prompt-text").value = prompt.prompt;
        openModal("prompt-modal");
        setFeedback("prompt-modal-feedback", "", "");
        document.getElementById("prompt-text").focus();
    }

    function closePromptModal(clearFeedback = true) {
        resetPromptForm();
        closeModal("prompt-modal");
        if (clearFeedback) {
            setFeedback("prompt-modal-feedback", "", "");
        }
    }

    function resetPromptForm() {
        document.getElementById("prompt-form").reset();
        document.getElementById("prompt-style").disabled = false;
    }

    function openAiFormForCreate() {
        resetAiForm();
        document.getElementById("ai-modal-title").textContent = "新建 AI Provider";
        openModal("ai-modal");
        setFeedback("ai-modal-feedback", "", "");
        document.getElementById("ai-name").focus();
    }

    function openAiFormForEdit(id) {
        const provider = state.aiProviders.find((item) => item.id === id);
        if (!provider) {
            return;
        }
        document.getElementById("ai-modal-title").textContent = `编辑 AI Provider：${provider.name || provider.id}`;
        openModal("ai-modal");
        document.getElementById("ai-id").value = provider.id;
        document.getElementById("ai-name").value = provider.name || "";
        document.getElementById("ai-request-timeout-seconds").value = providerRequestTimeoutSeconds(provider);
        document.getElementById("ai-base-url").value = provider.baseUrl || "";
        document.getElementById("ai-api-kind").value = providerApiKind(provider);
        document.getElementById("ai-model").value = provider.model || "";
        document.getElementById("ai-effort").value = provider.effort || "";
        document.getElementById("ai-api-key").value = provider.apiKey || "";
        setFeedback("ai-modal-feedback", "", "");
        document.getElementById("ai-name").focus();
    }

    function closeAiModal(clearFeedback = true) {
        resetAiForm();
        closeModal("ai-modal");
        if (clearFeedback) {
            setFeedback("ai-modal-feedback", "", "");
        }
    }

    function resetAiForm() {
        document.getElementById("ai-form").reset();
        document.getElementById("ai-id").value = "";
        document.getElementById("ai-request-timeout-seconds").value = "45";
        document.getElementById("ai-api-kind").value = "CHAT_COMPLETIONS";
    }

    function openModal(id) {
        document.getElementById(id).hidden = false;
        document.body.classList.add("modal-open");
    }

    function closeModal(id) {
        document.getElementById(id).hidden = true;
        if (document.querySelectorAll(".modal-shell:not([hidden])").length === 0) {
            document.body.classList.remove("modal-open");
        }
    }

    async function fetchJson(url, options = {}, redirectOnUnauthorized = true) {
        const response = await fetch(url, {
            headers: {
                Accept: "application/json",
                "Content-Type": "application/json"
            },
            ...options
        });
        if (response.status === 401 && redirectOnUnauthorized) {
            redirectToLogin();
            throw new Error("登录已失效。");
        }
        if (!response.ok) {
            throw new Error(await errorMessage(response));
        }
        if (response.status === 204) {
            return null;
        }
        return response.json();
    }

    function validateAiBaseUrl(value) {
        if (!value) {
            return "BaseURL 不能为空。";
        }
        let url;
        try {
            url = new URL(value);
        } catch (error) {
            return "BaseURL 必须是合法 URL。";
        }
        if ((url.protocol !== "http:" && url.protocol !== "https:") || !url.host) {
            return "BaseURL 必须包含 http/https 协议和域名。";
        }
        const path = url.pathname.replace(/\/+$/, "").toLowerCase();
        if (!path.endsWith("/v1")) {
            return "BaseURL 应填写到 /v1，例如 https://api.openai.com/v1。";
        }
        return "";
    }

    async function errorMessage(response) {
        try {
            const payload = await response.json();
            return payload.message || payload.error || `HTTP ${response.status}`;
        } catch (error) {
            return `HTTP ${response.status}`;
        }
    }

    function showAdmin() {
        document.getElementById("admin-shell").hidden = false;
        document.getElementById("logout-button").hidden = false;
    }

    function redirectToLogin() {
        const next = `${window.location.pathname}${window.location.search}${window.location.hash}`;
        window.location.replace(`/admin/login?next=${encodeURIComponent(next)}`);
    }

    function setFeedback(id, message, className) {
        const node = document.getElementById(id);
        node.textContent = message || "";
        node.className = `feedback ${className || ""}`.trim();
    }

    function trimTrailingPunctuation(value) {
        return String(value ?? "").replace(/[。.!！?？,，;；:：]+$/u, "");
    }

    function formatTimestamp(value) {
        if (!value) {
            return "n/a";
        }
        return new Date(value).toLocaleString();
    }

    function formatBytes(value) {
        const bytes = Number(value || 0);
        if (bytes < 1024) {
            return `${bytes} B`;
        }
        if (bytes < 1024 * 1024) {
            return `${(bytes / 1024).toFixed(1)} KB`;
        }
        return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
    }

    function promptPreview(value) {
        const text = String(value ?? "").replace(/\s+/g, " ").trim();
        if (text.length <= 120) {
            return text;
        }
        return `${text.slice(0, 120)}...`;
    }

    function providerApiKind(provider) {
        return provider.apiKind || "CHAT_COMPLETIONS";
    }

    function providerRequestTimeoutSeconds(provider) {
        const value = Number(provider.requestTimeoutSeconds || 45);
        return Number.isFinite(value) && value > 0 ? value : 45;
    }

    function providerRequestTimeoutLabel(provider) {
        return `${providerRequestTimeoutSeconds(provider)}s`;
    }

    function aiKindLabel(value) {
        return value === "RESPONSES" ? "Responses" : "Chat";
    }

    function escapeHtml(value) {
        return String(value ?? "")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll('"', "&quot;")
            .replaceAll("'", "&#39;");
    }

    init();
})();
