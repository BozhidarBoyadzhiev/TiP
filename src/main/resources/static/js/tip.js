"use strict";

// Tip page. Every piece of text that comes from the server or the user is set with textContent.
(function () {
    const MESSAGE_MAX = 280;
    // The server puts a per request CSP nonce on this script tag. The PayPal SDK needs it too.
    const CSP_NONCE = document.currentScript ? document.currentScript.nonce : "";

    const ERROR_MESSAGES = {
        DEVICE_NOT_FOUND: "This panda could not be found. Check the tag and try again.",
        AMOUNT_OUT_OF_RANGE: "Please choose an amount within the allowed range.",
        CURRENCY_NOT_SUPPORTED: "This currency is not supported.",
        VALIDATION_FAILED: "Something in the form is not right. Please check it and try again.",
        RATE_LIMITED: "Too many tips from this network. Please wait a minute and try again.",
        PAYPAL_ORDER_FAILED: "PayPal could not start the payment. Please try again.",
        PAYPAL_CAPTURE_FAILED: "PayPal could not complete the payment. You have not been charged twice. Please try again.",
        PAYPAL_ORDER_NOT_FOUND: "PayPal could not find this payment. Please start again.",
        TIP_NOT_FOUND: "This tip could not be found. Please start again.",
        TIP_CLOSED: "This tip can no longer be completed. Please start again.",
    };
    const GENERIC_ERROR = "Something went wrong. Please try again.";

    const state = { config: null, amountMinor: null, tipId: null, formatter: null, fractionDigits: 2 };

    const el = (id) => document.getElementById(id);

    function show(sectionId) {
        for (const id of ["loading", "tip-form", "success", "pending", "fatal"]) {
            el(id).hidden = id !== sectionId;
        }
        const section = el(sectionId);
        if (section.tabIndex === -1) {
            section.focus();
        }
    }

    function fatal(message) {
        el("fatal-message").textContent = message;
        show("fatal");
    }

    function formError(message) {
        const node = el("form-error");
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

    function messageFor(code) {
        return (code && ERROR_MESSAGES[code]) || GENERIC_ERROR;
    }

    function slugFromPath() {
        const parts = window.location.pathname.split("/").filter(Boolean);
        return parts.length >= 2 && parts[0] === "t" ? decodeURIComponent(parts[1]) : null;
    }

    function formatMinor(minor) {
        return state.formatter.format(minor / Math.pow(10, state.fractionDigits));
    }

    // Parses "2", "2.5" or "2,50" into minor units without floating point arithmetic.
    function parseAmount(text) {
        const digits = state.fractionDigits;
        const trimmed = text.trim().replace(",", ".");
        const pattern = digits > 0 ? new RegExp("^\\d{1,7}(\\.\\d{0," + digits + "})?$") : /^\d{1,9}$/;
        if (!pattern.test(trimmed)) {
            return null;
        }
        const [whole, fraction = ""] = trimmed.split(".");
        return Number(whole) * Math.pow(10, digits) + Number(fraction.padEnd(digits, "0") || "0");
    }

    function validAmount(minor) {
        return Number.isInteger(minor) && minor >= state.config.minMinor && minor <= state.config.maxMinor;
    }

    function selectPreset(button, minor) {
        for (const other of el("presets").querySelectorAll("button")) {
            other.setAttribute("aria-pressed", String(other === button));
        }
        el("custom-amount").value = "";
        state.amountMinor = minor;
        formError(null);
    }

    function renderForm(config) {
        state.config = config;
        state.formatter = new Intl.NumberFormat(undefined, { style: "currency", currency: config.currency });
        state.fractionDigits = state.formatter.resolvedOptions().maximumFractionDigits;

        el("title").textContent = "Tip " + config.displayName;
        document.title = "Tip " + config.displayName;
        el("currency-code").textContent = config.currency;
        el("amount-hint").textContent =
            "Between " + formatMinor(config.minMinor) + " and " + formatMinor(config.maxMinor) + ".";

        const presets = el("presets");
        for (const minor of config.presetAmountsMinor) {
            const button = document.createElement("button");
            button.type = "button";
            button.textContent = formatMinor(minor);
            button.setAttribute("aria-pressed", "false");
            button.addEventListener("click", () => selectPreset(button, minor));
            presets.appendChild(button);
        }

        el("custom-amount").addEventListener("input", (event) => {
            for (const button of presets.querySelectorAll("button")) {
                button.setAttribute("aria-pressed", "false");
            }
            state.amountMinor = parseAmount(event.target.value);
            formError(null);
        });

        const message = el("message");
        message.addEventListener("input", () => {
            el("message-count").textContent = message.value.length + " / " + MESSAGE_MAX;
        });

        show("tip-form");
    }

    function loadPayPal(config) {
        return new Promise((resolve, reject) => {
            const params = new URLSearchParams({
                "client-id": config.paypalClientId,
                currency: config.currency,
                intent: "capture",
                components: "buttons",
            });
            const script = document.createElement("script");
            script.src = "https://www.paypal.com/sdk/js?" + params.toString();
            script.nonce = CSP_NONCE;
            script.setAttribute("data-csp-nonce", CSP_NONCE);
            script.onload = resolve;
            script.onerror = reject;
            document.head.appendChild(script);
        });
    }

    async function createOrder() {
        const response = await fetch("/api/public/devices/" + encodeURIComponent(state.slug) + "/tips", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                amountMinor: state.amountMinor,
                currency: state.config.currency,
                message: el("message").value,
            }),
        });
        if (!response.ok) {
            const message = messageFor(await problemCode(response));
            formError(message);
            throw new Error(message);
        }
        const body = await response.json();
        state.tipId = body.tipId;
        return body.paypalOrderId;
    }

    async function onApprove(data, actions) {
        const response = await fetch("/api/public/tips/" + encodeURIComponent(state.tipId) + "/capture", {
            method: "POST",
        });
        if (response.status === 422 && (await problemCode(response)) === "INSTRUMENT_DECLINED") {
            formError("Your payment method was declined. Please choose another one.");
            return actions.restart();
        }
        if (!response.ok) {
            formError(messageFor(await problemCode(response)));
            return;
        }
        const body = await response.json();
        if (body.status === "CAPTURED") {
            show("success");
        } else if (body.status === "PENDING") {
            show("pending");
        } else {
            formError(GENERIC_ERROR);
        }
    }

    function renderButtons() {
        window.paypal
            .Buttons({
                style: { layout: "vertical", shape: "rect", label: "pay" },
                onClick: (data, actions) => {
                    if (!validAmount(state.amountMinor)) {
                        formError(ERROR_MESSAGES.AMOUNT_OUT_OF_RANGE + " " + el("amount-hint").textContent);
                        return actions.reject();
                    }
                    formError(null);
                    return actions.resolve();
                },
                createOrder: createOrder,
                onApprove: onApprove,
                onCancel: () => formError("Payment cancelled. You have not been charged."),
                onError: () => {
                    if (el("form-error").hidden) {
                        formError(GENERIC_ERROR);
                    }
                },
            })
            .render("#paypal-buttons");
    }

    async function start() {
        state.slug = slugFromPath();
        if (!state.slug) {
            fatal(ERROR_MESSAGES.DEVICE_NOT_FOUND);
            return;
        }
        let response;
        try {
            response = await fetch("/api/public/devices/" + encodeURIComponent(state.slug));
        } catch (e) {
            fatal("Could not reach the server. Check your connection and try again.");
            return;
        }
        if (!response.ok) {
            fatal(messageFor(response.status === 400 ? "DEVICE_NOT_FOUND" : await problemCode(response)));
            return;
        }
        const config = await response.json();
        renderForm(config);
        try {
            await loadPayPal(config);
            renderButtons();
        } catch (e) {
            formError("PayPal could not be loaded. Please try again later.");
        }
    }

    document.addEventListener("DOMContentLoaded", start);
})();
