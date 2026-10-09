"use strict";

// Owner sign in. Posts the form with fetch so a failure shows a message on the page instead of raw JSON.
// Every message is set with textContent.
(function () {
    const ERROR_MESSAGES = {
        LOGIN_FAILED: "Email or password is incorrect.",
        RATE_LIMITED: "Too many sign in attempts. Please wait a minute and try again.",
        CSRF_TOKEN_INVALID: "This page has expired. Please reload it and try again.",
    };
    const GENERIC_ERROR = "Something went wrong. Please try again.";

    const el = (id) => document.getElementById(id);

    function showError(message) {
        const node = el("login-error");
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

    async function submit(event) {
        event.preventDefault();
        const form = event.target;
        const button = form.querySelector("button[type=submit]");
        showError(null);
        button.disabled = true;
        try {
            const response = await fetch(form.action, {
                method: "POST",
                body: new URLSearchParams(new FormData(form)),
                credentials: "same-origin",
                redirect: "manual",
            });
            // Success answers with a redirect to the owner page, which fetch reports as opaque.
            if (response.type === "opaqueredirect") {
                window.location.assign("/owner");
                return;
            }
            const code = await problemCode(response);
            showError((code && ERROR_MESSAGES[code]) || GENERIC_ERROR);
        } catch (e) {
            showError("Could not reach the server. Check your connection and try again.");
        } finally {
            button.disabled = false;
        }
    }

    document.addEventListener("DOMContentLoaded", () => {
        el("signed-out").hidden = !new URLSearchParams(window.location.search).has("signedOut");
        el("login-form").addEventListener("submit", submit);
    });
})();
