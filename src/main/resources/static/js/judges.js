"use strict";

// The access code page. Posts the code with fetch, so a wrong code or a rate limit shows on the page instead of
// raw JSON. The panda wakes and follows the code as it is typed. Every message is set with textContent.
(function () {
    const CODE_LENGTH = 20;
    const GROUP = 5;
    const MESSAGES = {
        LOGIN_FAILED: "That code didn't work. Check it and try again.",
        CSRF_TOKEN_INVALID: "This page has expired. Please reload it and try again.",
    };
    const GENERIC_ERROR = "Something went wrong. Please try again.";
    const OFFLINE = "Could not reach the server. Check your connection and try again.";

    const el = (id) => document.getElementById(id);

    // Upper case, letters and digits only, the TIP prefix dropped from a pasted TIP-XXXXX-... code, shown in the
    // code's own groups of five. The server normalises again, Crockford's O, I and L included.
    function clean(value) {
        let code = value.toUpperCase().replace(/[^0-9A-Z]/g, "");
        if (code.length > CODE_LENGTH && code.startsWith("TIP")) {
            code = code.slice(3);
        }
        return code.slice(0, CODE_LENGTH);
    }

    function grouped(code) {
        return code.replace(new RegExp("(.{" + GROUP + "})(?=.)", "g"), "$1 ");
    }

    function look() {
        const typed = clean(el("code").value).length;
        const x = (typed / CODE_LENGTH - 0.5) * 6;
        el("pupils").style.transform = "translate(" + x + "px," + (typed ? 2 : 0) + "px)";
    }

    function show(id, message) {
        ["n-bad", "n-slow", "n-ended"].forEach((other) => {
            if (other !== id) {
                el(other).hidden = true;
            }
        });
        if (id) {
            if (message) {
                el(id).textContent = message;
            }
            el(id).hidden = false;
        }
    }

    function busy(on) {
        const input = el("code");
        const button = el("code-btn");
        input.disabled = on;
        button.disabled = on;
        button.textContent = on ? "Opening your sandbox…" : "Open my sandbox";
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
        const form = el("code-form");
        const input = el("code");
        const code = clean(input.value);
        input.classList.remove("bad");
        if (code.length === 0) {
            input.classList.add("bad");
            show("n-bad", MESSAGES.LOGIN_FAILED);
            input.focus();
            return;
        }
        const body = new URLSearchParams(new FormData(form));
        body.set("code", code);
        show(null);
        busy(true);
        try {
            const response = await fetch(form.action, {
                method: "POST",
                body,
                credentials: "same-origin",
                redirect: "manual",
            });
            // Success answers with a redirect to the dashboard, which fetch reports as opaque.
            if (response.type === "opaqueredirect") {
                window.location.assign("/owner");
                return;
            }
            busy(false);
            if (response.status === 429) {
                show("n-slow");
                el("code-btn").disabled = true;
                return;
            }
            const problem = await problemCode(response);
            input.classList.toggle("bad", problem === "LOGIN_FAILED");
            show("n-bad", (problem && MESSAGES[problem]) || GENERIC_ERROR);
            input.focus();
        } catch (e) {
            busy(false);
            show("n-bad", OFFLINE);
        }
    }

    document.addEventListener("DOMContentLoaded", () => {
        const input = el("code");
        const wrap = el("code-wrap");
        const query = new URLSearchParams(window.location.search);
        show(query.has("sandboxExpired") ? "n-ended" : null);

        try {
            el("time-zone").value = Intl.DateTimeFormat().resolvedOptions().timeZone || "";
        } catch (e) {
            el("time-zone").value = "";
        }

        const realPanda = el("real-panda");
        el("real-panda-line").hidden = !realPanda.getAttribute("href");

        input.addEventListener("focus", () => {
            wrap.classList.add("awake");
            look();
        });
        input.addEventListener("blur", () => {
            if (!input.value) {
                wrap.classList.remove("awake");
            }
            el("pupils").style.transform = "";
        });
        input.addEventListener("input", () => {
            input.value = grouped(clean(input.value));
            input.classList.remove("bad");
            el("n-bad").hidden = true;
            if (!el("n-slow").hidden) {
                el("n-slow").hidden = true;
                el("code-btn").disabled = false;
            }
            look();
        });
        el("code-form").addEventListener("submit", submit);
    });
})();
