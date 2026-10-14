"use strict";

// Opens the public sample dashboard from a link: posts /demo/session with the page's CSRF token, then goes to
// the dashboard. Without script the button does the same as a plain form post. Messages are set with textContent.
(function () {
    const ERROR_MESSAGES = {
        RATE_LIMITED: "Lots of people are looking right now. Wait a minute, then try again.",
        DEMO_UNAVAILABLE: "The sample isn't ready yet. Try again in a moment.",
        CSRF_TOKEN_INVALID: "This page has expired. Please reload it and try again.",
    };
    const GENERIC_ERROR = "Something went wrong. Please try again.";

    const el = (id) => document.getElementById(id);

    function showError(message) {
        const node = el("demo-error");
        node.textContent = message || "";
        node.hidden = !message;
    }

    async function problemCode(response) {
        try {
            const body = await response.json();
            return typeof body.code === "string" ? body.code : null;
        } catch (e) {
            return null;
        }
    }

    async function open() {
        const form = el("demo-form");
        const button = el("demo-btn");
        showError(null);
        button.disabled = true;
        button.textContent = "Opening the sample…";
        try {
            const response = await fetch(form.action, {
                method: "POST",
                body: new URLSearchParams(new FormData(form)),
                credentials: "same-origin",
                redirect: "manual",
            });
            // Success answers with a redirect to the dashboard, which fetch reports as opaque.
            if (response.type === "opaqueredirect") {
                window.location.replace("/owner");
                return;
            }
            const code = await problemCode(response);
            showError((code && ERROR_MESSAGES[code]) || GENERIC_ERROR);
        } catch (e) {
            showError("Could not reach the server. Check your connection and try again.");
        }
        button.disabled = false;
        button.textContent = "Open the sample dashboard";
    }

    document.addEventListener("DOMContentLoaded", () => {
        el("demo-form").addEventListener("submit", (event) => {
            event.preventDefault();
            open();
        });
        open();
    });
})();
