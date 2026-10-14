"use strict";

// The resume link's page. The token is in the fragment, which never reaches the server; it is read once and
// taken out of the address bar. It is posted only when the person taps, never on load, because link previews and
// mail scanners fetch the page and some run its script. Every message is set with textContent.
(function () {
    const SLOW = "Too many tries from this network. Wait a few minutes, then try again.";
    const OFFLINE = "Could not reach the server. Check your connection and try again.";

    const el = (id) => document.getElementById(id);
    const meta = (name) => document.querySelector("meta[name=\"" + name + "\"]").getAttribute("content");

    function tokenFromFragment() {
        const token = new URLSearchParams(window.location.hash.slice(1)).get("t");
        history.replaceState(null, "", window.location.pathname);
        return token;
    }

    function failed() {
        el("ready").hidden = true;
        el("failed").hidden = false;
        el("resume-wrap").classList.remove("awake");
    }

    function showError(message) {
        const node = el("n-error");
        node.textContent = message || "";
        node.hidden = !message;
    }

    function busy(on) {
        const button = el("resume-btn");
        button.disabled = on;
        button.textContent = on ? "Opening your sandbox…" : "Continue to your sandbox";
    }

    async function resume(token) {
        const headers = { "Content-Type": "application/json" };
        headers[meta("csrf-header")] = meta("csrf-token");
        showError(null);
        busy(true);
        try {
            const response = await fetch("/judges/resume", {
                method: "POST",
                headers,
                body: JSON.stringify({ token }),
                credentials: "same-origin",
            });
            if (response.ok) {
                window.location.replace("/owner");
                return;
            }
            // A wait helps only after a rate limit; any other answer means this link will not work.
            if (response.status === 429) {
                busy(false);
                showError(SLOW);
                return;
            }
            failed();
        } catch (e) {
            busy(false);
            showError(OFFLINE);
        }
    }

    document.addEventListener("DOMContentLoaded", () => {
        const token = tokenFromFragment();
        if (!token) {
            failed();
            return;
        }
        el("ready").hidden = false;
        el("resume-wrap").classList.add("awake");
        el("resume-btn").addEventListener("click", () => resume(token));
    });
})();
