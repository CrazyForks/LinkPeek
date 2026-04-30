(function () {
    const state = {
        prompts: [],
        aiProviders: [],
        logRefreshTimer: null
    };

    function init() {
        bindLogout();
        bindPurge();
        bindPromptForm();
        bindProviderForm();
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
            setFeedback("prompt-modal-feedback", "正在保存提示词...", "");
            try {
                await fetchJson(`/api/admin/prompts/${encodeURIComponent(style)}`, {
                    method: "PUT",
                    body: JSON.stringify({prompt})
                });
                closePromptModal(false);
                await loadPrompts();
                setFeedback("prompt-feedback", "提示词已保存。", "is-success");
            } catch (error) {
                setFeedback("prompt-modal-feedback", error.message, "is-error");
            }
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

    function bindAiForm() {
        document.getElementById("ai-new-button").addEventListener("click", openAiFormForCreate);
        document.getElementById("ai-cancel-button").addEventListener("click", closeAiModal);
        document.getElementById("ai-form").addEventListener("submit", async (event) => {
            event.preventDefault();
            const id = document.getElementById("ai-id").value;
            const payload = {
                name: document.getElementById("ai-name").value.trim(),
                enabled: document.getElementById("ai-enabled").checked,
                sortOrder: Number(document.getElementById("ai-sort-order").value || 0),
                baseUrl: document.getElementById("ai-base-url").value.trim(),
                apiKind: document.getElementById("ai-api-kind").value,
                model: document.getElementById("ai-model").value.trim(),
                effort: document.getElementById("ai-effort").value.trim(),
                apiKey: document.getElementById("ai-api-key").value.trim()
            };
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
        await Promise.all([loadPrompts(), loadProviderConfig(), loadAiProviders(), loadLogs()]);
    }

    async function loadPrompts() {
        state.prompts = await fetchJson("/api/admin/prompts");
        renderPrompts();
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
            body.innerHTML = `<tr><td colspan="3" class="muted">暂无提示词</td></tr>`;
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
            body.innerHTML = `<tr><td colspan="7" class="muted">暂无 AI Provider</td></tr>`;
            return;
        }
        body.innerHTML = state.aiProviders.map((provider) => `
            <tr>
                <td>${escapeHtml(provider.name)}</td>
                <td>${escapeHtml(provider.baseUrl)}</td>
                <td>${escapeHtml(aiKindLabel(providerApiKind(provider)))}</td>
                <td>${escapeHtml(provider.model)}</td>
                <td>${provider.sortOrder}</td>
                <td>${provider.enabled ? "启用" : "禁用"}</td>
                <td>
                    <div class="row-actions">
                        <button type="button" data-test-ai="${provider.id}" class="secondary test-button">测试</button>
                        <button type="button" data-edit-ai="${provider.id}" class="secondary">编辑</button>
                        <button type="button" data-delete-ai="${provider.id}" class="danger">删除</button>
                    </div>
                </td>
            </tr>
        `).join("");
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
        document.getElementById("prompt-modal-title").textContent = "新建提示词";
        document.getElementById("prompt-style").disabled = false;
        openModal("prompt-modal");
        setFeedback("prompt-modal-feedback", "", "");
        document.getElementById("prompt-style").focus();
    }

    function openPromptModalForEdit(prompt) {
        resetPromptForm();
        document.getElementById("prompt-modal-title").textContent = `编辑提示词：${prompt.style}`;
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
        document.getElementById("ai-enabled").checked = Boolean(provider.enabled);
        document.getElementById("ai-sort-order").value = provider.sortOrder || 0;
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
        document.getElementById("ai-sort-order").value = "100";
        document.getElementById("ai-api-kind").value = "CHAT_COMPLETIONS";
        document.getElementById("ai-enabled").checked = true;
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
