"use strict";

// Tip page. Every piece of text that comes from the server or the user is set with textContent.
(function () {
    const MESSAGE_MAX = 280;
    const OPENING_MS = 5000;
    const MESSAGE_NEAR = 250;
    // The server puts a per request CSP nonce on this script tag. The PayPal SDK needs it too.
    const CSP_NONCE = document.currentScript ? document.currentScript.nonce : "";
    // ?embed: the page sits in the side by side view beside the panda. It only changes what is shown.
    if (new URLSearchParams(window.location.search).has("embed")) {
        document.documentElement.classList.add("embedded");
    }

    const ERROR_MESSAGES = {
        DEVICE_NOT_FOUND: "This panda could not be found. Check the tag and try again.",
        AMOUNT_OUT_OF_RANGE: "Please choose an amount within the allowed range.",
        CURRENCY_NOT_SUPPORTED: "This currency is not supported.",
        VALIDATION_FAILED: "Something in the form is not right. Please check it and try again.",
        RATE_LIMITED: "Lots of tips from this network just now. Please wait a minute and try again.",
        PAYPAL_ORDER_FAILED: "PayPal could not start the payment. Please try again.",
        PAYPAL_CAPTURE_FAILED: "PayPal could not complete the payment. You have not been charged twice. Please try again.",
        PAYPAL_ORDER_NOT_FOUND: "PayPal could not find this payment. Please start again.",
        TIP_NOT_FOUND: "This tip could not be found. Please start again.",
        TIP_CLOSED: "This tip can no longer be completed. Please start again.",
        INSTRUMENT_DECLINED: "Your payment method was declined. Please try another card or pay with PayPal.",
    };
    const GENERIC_ERROR = "Something went wrong. Please try again.";
    const NETWORK_ERROR = "We could not reach the server. Check your connection and try again.";

    const state = {
        slug: null,
        config: null,
        tipId: null,
        sentMinor: null,
        // "preset" or "custom"; presetMinor is the pressed preset, lastPreset the one to return to.
        mode: "preset",
        presetMinor: null,
        lastPreset: null,
        lastValid: null,
        buttons: null,
        retry: null,
        // Set when the capture was declined, so cancelling PayPal's restarted flow keeps the decline note.
        declined: false,
        digits: 2,
        unit: 100,
        whole: null,
        exact: null,
        // The "Opening PayPal…" line: its timer and the height of the buttons when it was shown.
        opening: { timer: null, height: 0 },
    };

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

    function fatal(message, retry) {
        el("fatal-message").textContent = message;
        el("fatal-retry").hidden = !retry;
        show("fatal");
    }

    // A paper note above the PayPal buttons. Try again runs the retry, or just clears the note.
    function formError(message, retry) {
        el("form-error-text").textContent = message || "";
        el("form-error").hidden = !message;
        state.retry = retry || null;
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

    // ---------- money ----------

    // Whole amounts show without decimals (£5), others with all of the currency's (£2.50).
    function formatMinor(minor) {
        const formatter = minor % state.unit === 0 ? state.whole : state.exact;
        return formatter.format(minor / state.unit);
    }

    // The amount as the input shows it: "7" or "7.50", built from integers.
    function inputText(minor) {
        const fraction = minor % state.unit;
        const whole = (minor - fraction) / state.unit;
        return fraction === 0 ? String(whole) : whole + "." + String(fraction).padStart(state.digits, "0");
    }

    // Parses "7", "7.5", "7.50" or "7,50" into minor units by joining the digit strings, never through a
    // floating point number (4.35 * 100 is 434.99999999999994). Returns { empty } for nothing typed,
    // { minor } for a number, or { malformed } for anything else.
    function parseAmount(text, digits) {
        const trimmed = text.trim().replace(",", ".");
        if (trimmed === "") {
            return { empty: true };
        }
        const match = /^(\d+)(?:\.(\d*))?$/.exec(trimmed);
        if (!match || (match[2] !== undefined && match[2].length > digits)) {
            return { malformed: true };
        }
        const whole = match[1].replace(/^0+(?=\d)/, "");
        // Beyond 12 whole digits the amount is above any maximum, and the sum below stays exact.
        if (whole.length > 12) {
            return { minor: Number.MAX_SAFE_INTEGER };
        }
        const fraction = (match[2] || "").padEnd(digits, "0");
        return { minor: Number(whole + fraction) };
    }

    // ---------- the selection ----------

    // { minor } for a valid amount, otherwise { reason } (null when there is simply nothing to tip yet).
    function selection() {
        const { minMinor, maxMinor } = state.config;
        if (state.mode === "preset") {
            return state.presetMinor === null ? { reason: null } : { minor: state.presetMinor };
        }
        const parsed = parseAmount(el("custom-amount").value, state.digits);
        if (parsed.empty) {
            return { reason: null };
        }
        if (parsed.malformed) {
            return { reason: state.digits > 0 ? "Use a number like 7 or 7.50." : "Use a whole number like 7." };
        }
        if (parsed.minor < minMinor) {
            return { reason: "The smallest tip is " + formatMinor(minMinor) + ".", minor: parsed.minor };
        }
        if (parsed.minor > maxMinor) {
            return { reason: "The largest tip is " + formatMinor(maxMinor) + ".", minor: parsed.minor };
        }
        return { minor: parsed.minor };
    }

    function validMinor() {
        const current = selection();
        return current.reason === undefined ? current.minor : null;
    }

    function update(bump) {
        const current = selection();
        const valid = current.reason === undefined;
        if (valid) {
            state.lastValid = current.minor;
        }

        for (const button of el("presets").querySelectorAll(".amt")) {
            const pressed = state.mode === "preset" && Number(button.dataset.minor) === state.presetMinor;
            button.setAttribute("aria-pressed", String(pressed));
        }

        const own = el("own");
        const hint = el("amount-hint");
        const invalid = typeof current.reason === "string";
        own.classList.toggle("active", state.mode === "custom" && valid);
        own.classList.toggle("invalid", invalid);
        el("custom-amount").setAttribute("aria-invalid", String(invalid));
        hint.classList.toggle("warn", invalid);
        hint.textContent = invalid ? current.reason : defaultHint();

        const base = stepBase(current);
        el("minus").disabled = base !== null && base <= state.config.minMinor;
        el("plus").disabled = base !== null && base >= state.config.maxMinor;

        const total = el("total");
        if (valid) {
            el("total-text").textContent = "You're tipping";
            const text = formatMinor(current.minor);
            if (total.textContent !== text) {
                total.textContent = text;
                if (bump) {
                    total.classList.remove("bump");
                    void total.offsetWidth;
                    total.classList.add("bump");
                }
            }
        } else {
            el("total-text").textContent = "Pick an amount to tip";
            total.textContent = "";
        }
        syncButtons(valid);
    }

    function defaultHint() {
        return "Anything from " + formatMinor(state.config.minMinor) + " to " + formatMinor(state.config.maxMinor) + ".";
    }

    // The amount the stepper moves from: a typed number even out of bounds, otherwise the last valid amount.
    function stepBase(current) {
        if (current.minor !== undefined) {
            return current.minor;
        }
        return state.lastValid;
    }

    // From any amount, + goes to the next whole amount above it and − to the one below, within the bounds.
    function step(direction) {
        const { minMinor, maxMinor } = state.config;
        const base = stepBase(selection());
        let next;
        if (base === null) {
            next = minMinor;
        } else {
            const below = base - (base % state.unit);
            next = direction > 0 ? below + state.unit : base % state.unit === 0 ? base - state.unit : below;
        }
        next = Math.min(maxMinor, Math.max(minMinor, next));
        state.mode = "custom";
        el("custom-amount").value = inputText(next);
        update(true);
    }

    function selectPreset(minor) {
        state.mode = "preset";
        state.presetMinor = minor;
        state.lastPreset = minor;
        el("custom-amount").value = "";
        formError(null);
        update(true);
    }

    // ---------- PayPal buttons ----------

    // The buttons live in PayPal's iframe, so CSS cannot stop a click: the SDK's own actions disable them.
    function syncButtons(valid) {
        el("paypal-buttons").setAttribute("aria-disabled", String(!valid));
        if (state.buttons) {
            if (valid) {
                state.buttons.enable();
            } else {
                state.buttons.disable();
            }
        }
    }

    // ---------- opening PayPal ----------

    // PayPal takes a moment to open its card form or window and shows nothing meanwhile, so a short line says it
    // is on its way. It goes when the buttons change height (the card form opened), when the flow comes back,
    // or after OPENING_MS, whichever is first.
    function showOpening() {
        clearOpening();
        state.opening.height = el("paypal-buttons").offsetHeight;
        el("paypal-opening").textContent = "Opening PayPal…";
        state.opening.timer = setTimeout(clearOpening, OPENING_MS);
    }

    function clearOpening() {
        clearTimeout(state.opening.timer);
        state.opening.timer = null;
        el("paypal-opening").textContent = "";
    }

    function watchButtonsHeight() {
        new ResizeObserver(() => {
            if (state.opening.timer !== null && el("paypal-buttons").offsetHeight !== state.opening.height) {
                clearOpening();
            }
        }).observe(el("paypal-buttons"));
    }

    // ---------- rendering ----------

    function renderForm(config) {
        state.config = config;
        state.exact = new Intl.NumberFormat(undefined, { style: "currency", currency: config.currency });
        state.digits = state.exact.resolvedOptions().maximumFractionDigits;
        state.unit = Math.pow(10, state.digits);
        state.whole = new Intl.NumberFormat(undefined, {
            style: "currency",
            currency: config.currency,
            minimumFractionDigits: 0,
            maximumFractionDigits: 0,
        });

        if (!window.TipBrand.isBrandName(config.displayName)) {
            el("device-name").textContent = config.displayName.trim();
            el("for").hidden = false;
        }

        const symbol = state.whole.formatToParts(1).find((part) => part.type === "currency");
        el("currency-symbol").textContent = symbol ? symbol.value : config.currency;
        el("custom-amount").setAttribute("aria-label", "Your own amount in " + config.currency);
        el("minus").setAttribute("aria-label", formatMinor(state.unit) + " less");
        el("plus").setAttribute("aria-label", formatMinor(state.unit) + " more");

        const presets = el("presets");
        for (const minor of config.presetAmountsMinor) {
            const button = document.createElement("button");
            button.type = "button";
            button.className = "amt";
            button.dataset.minor = String(minor);
            button.textContent = formatMinor(minor);
            button.addEventListener("click", () => selectPreset(minor));
            presets.appendChild(button);
        }
        const amounts = config.presetAmountsMinor;
        if (amounts.length > 0) {
            state.presetMinor = amounts[Math.min(1, amounts.length - 1)];
            state.lastPreset = state.presetMinor;
        } else {
            state.mode = "custom";
        }

        const input = el("custom-amount");
        input.addEventListener("focus", () => {
            if (state.mode === "preset") {
                state.mode = "custom";
                input.value = state.presetMinor === null ? "" : inputText(state.presetMinor);
                input.select();
                update(false);
            }
        });
        input.addEventListener("input", () => {
            state.mode = "custom";
            update(true);
        });
        input.addEventListener("blur", () => {
            if (input.value.trim() === "" && state.lastPreset !== null) {
                selectPreset(state.lastPreset);
            }
        });
        el("minus").addEventListener("click", () => step(-1));
        el("plus").addEventListener("click", () => step(1));

        setUpNote();

        el("form-error-retry").addEventListener("click", () => {
            const retry = state.retry;
            formError(null);
            if (retry) {
                retry();
            } else {
                el("amount-label").scrollIntoView({ block: "start", behavior: "smooth" });
            }
        });

        update(false);
        show("tip-form");
    }

    // The panda wakes, blushes and follows the caret. It only watches focus and typing; nothing leaves the page.
    function setUpNote() {
        const wrap = el("note-wrap");
        const message = el("message");
        const count = el("message-count");
        const pupils = el("pupils");
        const look = () => {
            const ratio = message.value.length ? (message.selectionStart % 32) / 32 : 0.5;
            pupils.style.transform =
                "translate(" + (ratio - 0.5) * 6 + "px, " + (message.value.length ? 2 : 0) + "px)";
        };
        message.addEventListener("focus", () => {
            wrap.classList.add("awake");
            look();
        });
        message.addEventListener("blur", () => {
            if (!message.value) {
                wrap.classList.remove("awake");
            }
            pupils.style.transform = "";
        });
        message.addEventListener("input", () => {
            count.textContent = message.value.length + " / " + MESSAGE_MAX;
            count.classList.toggle("near", message.value.length > MESSAGE_NEAR);
            look();
        });
        message.addEventListener("keyup", look);
        message.addEventListener("click", look);
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
        const amountMinor = validMinor();
        if (amountMinor === null) {
            throw new Error("No valid amount");
        }
        let response;
        try {
            response = await fetch("/api/public/devices/" + encodeURIComponent(state.slug) + "/tips", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    amountMinor: amountMinor,
                    currency: state.config.currency,
                    message: el("message").value,
                }),
            });
        } catch (e) {
            formError(NETWORK_ERROR);
            throw e;
        }
        if (!response.ok) {
            const message = messageFor(await problemCode(response));
            formError(message);
            throw new Error(message);
        }
        const body = await response.json();
        state.tipId = body.tipId;
        state.sentMinor = amountMinor;
        return body.paypalOrderId;
    }

    async function onApprove(data, actions) {
        let response;
        try {
            response = await fetch("/api/public/tips/" + encodeURIComponent(state.tipId) + "/capture", {
                method: "POST",
            });
        } catch (e) {
            // Capturing again is safe: the server sends PayPal the same request id.
            formError("We could not confirm your payment. Check your connection and try again.", () =>
                onApprove(data, actions),
            );
            return;
        }
        if (response.status === 422 && (await problemCode(response)) === "INSTRUMENT_DECLINED") {
            state.declined = true;
            formError(ERROR_MESSAGES.INSTRUMENT_DECLINED);
            return actions.restart();
        }
        if (!response.ok) {
            formError(messageFor(await problemCode(response)));
            return;
        }
        const body = await response.json();
        if (body.status === "CAPTURED") {
            el("paid-amount").textContent = formatMinor(state.sentMinor);
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
                style: { layout: "vertical", color: "gold", shape: "rect", borderRadius: 26, label: "pay" },
                onInit: (data, actions) => {
                    state.buttons = actions;
                    syncButtons(validMinor() !== null);
                },
                onClick: (data, actions) => {
                    state.declined = false;
                    if (validMinor() === null) {
                        update(false);
                        el("custom-amount").focus();
                        return actions.reject();
                    }
                    formError(null);
                    const resolved = actions.resolve();
                    showOpening();
                    return resolved;
                },
                createOrder: createOrder,
                onApprove: (data, actions) => {
                    clearOpening();
                    return onApprove(data, actions);
                },
                onCancel: () => {
                    clearOpening();
                    formError(
                        state.declined
                            ? ERROR_MESSAGES.INSTRUMENT_DECLINED
                            : "Payment cancelled. You have not been charged.",
                    );
                },
                onError: () => {
                    clearOpening();
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
        el("watch-panda").href = "/p/" + encodeURIComponent(state.slug);
        el("tip-again").addEventListener("click", () => window.location.reload());
        el("fatal-retry").addEventListener("click", () => window.location.reload());
        let response;
        try {
            response = await fetch("/api/public/devices/" + encodeURIComponent(state.slug));
        } catch (e) {
            fatal(NETWORK_ERROR, true);
            return;
        }
        if (!response.ok) {
            const code = response.status === 400 ? "DEVICE_NOT_FOUND" : await problemCode(response);
            fatal(messageFor(code), code !== "DEVICE_NOT_FOUND");
            return;
        }
        const config = await response.json();
        renderForm(config);
        try {
            await loadPayPal(config);
            watchButtonsHeight();
            renderButtons();
        } catch (e) {
            formError("PayPal could not be loaded. Please try again.", () => window.location.reload());
        }
    }

    document.addEventListener("DOMContentLoaded", start);
})();
