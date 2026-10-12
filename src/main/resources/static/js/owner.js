"use strict";

// The owner dashboard. Loads everything from /api/owner/*, then listens on /ws/owner (docs/OWNER_CHANNEL.md)
// and refetches what changed. Every value from the server is untrusted and goes into the page with
// textContent or createTextNode; elements are built with createElement only.
(function () {
    const PAGE_SIZE = 20;
    const HIDDEN_PANEL_SIZE = 5;
    // The client policy of docs/DEVICE_PROTOCOL.md, which the owner channel follows too.
    const BACKOFF_SECONDS = [1, 2, 4, 8, 16, 32, 60];
    const JITTER = 0.2;
    const STABLE_MS = 60000;
    const POLL_MS = 30000;
    const TICK_MS = 30000;
    const TOAST_MS = 3200;
    const MINUTE_MS = 60000;
    const HOUR_MS = 3600000;
    const SVG_NS = "http://www.w3.org/2000/svg";
    const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

    const KIND_NAMES = { WARM: "Warm", PLAYFUL: "Playful", REQUEST: "Request", PLAIN: "Plain", FLAG: "Hidden" };
    const REACTION_NAMES = { WARM: "heartbeats", PLAYFUL: "giggles", REQUEST: "noted pulses", PLAIN: "blinks" };
    const STATUS_NAMES = {
        CAPTURED: "Paid",
        PENDING: "Pending at PayPal",
        REFUNDED: "Refunded",
        REVERSED: "Reversed by PayPal",
    };
    const BADGES = {
        PENDING: ["pending", "Pending at PayPal"],
        REFUNDED: ["refunded", "Refunded"],
        REVERSED: ["reversed", "Reversed"],
    };
    // Filter chip to query: a kind filter shows only unflagged tips of that kind.
    const FILTERS = {
        ALL: "",
        WARM: "&kind=WARM&flagged=false",
        PLAYFUL: "&kind=PLAYFUL&flagged=false",
        REQUEST: "&kind=REQUEST&flagged=false",
        PLAIN: "&kind=PLAIN&flagged=false",
        HIDDEN: "&flagged=true",
    };
    const EMPTY = {
        ALL: ["No tips yet, the panda is waiting", "When someone taps the coin, their tip shows up here straight away."],
        HIDDEN: ["No hidden messages", "The panda hasn't had to cover its eyes."],
        KIND: ["Nothing here yet", "No tips of this kind so far."],
    };

    const el = (id) => document.getElementById(id);
    const csrf = {
        header: document.querySelector("meta[name='csrf-header']").content,
        token: document.querySelector("meta[name='csrf-token']").content,
    };

    const state = {
        zone: undefined,
        currency: null,
        filter: "ALL",
        tips: [],
        page: 0,
        total: 0,
        generation: 0,
        hidden: [],
        hiddenTotal: 0,
        devices: [],
        ui: new Map(),
        refunds: new Map(),
        socket: null,
        attempt: 0,
        openedAt: null,
        disconnected: false,
        replaced: false,
        reconnectTimer: null,
        pollTimer: null,
        toastTimer: null,
        leaving: false,
    };

    // ---------- HTTP ----------

    class SignedOut extends Error {}

    class HttpError extends Error {
        constructor(status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    function toLogin() {
        if (!state.leaving) {
            state.leaving = true;
            window.location.assign("/owner/login");
        }
    }

    async function request(path, options) {
        const response = await fetch(path, {
            credentials: "same-origin",
            cache: "no-store",
            method: (options && options.method) || "GET",
            headers: Object.assign({ Accept: "application/json" }, options && options.headers),
        });
        if (response.status === 401) {
            toLogin();
            throw new SignedOut();
        }
        return response;
    }

    async function getJson(path) {
        const response = await request(path);
        if (!response.ok) {
            throw new HttpError(response.status);
        }
        return response.json();
    }

    // At most one run of a task in flight; calls made meanwhile collapse into one more run after it.
    function coalesced(task) {
        let running = null;
        let again = false;
        return function run() {
            if (running) {
                again = true;
                return running;
            }
            running = (async () => {
                try {
                    do {
                        again = false;
                        await task();
                    } while (again);
                } finally {
                    running = null;
                }
            })();
            return running;
        };
    }

    // Background refreshes fail quietly; the next event, poll or reconnect tries again.
    function quietly(promise) {
        promise.catch(() => {});
    }

    // ---------- formatting ----------

    function money(amountMinor, currency) {
        const format = new Intl.NumberFormat(undefined, { style: "currency", currency });
        const digits = format.resolvedOptions().maximumFractionDigits;
        return format.format(amountMinor / Math.pow(10, digits));
    }

    function dateKey(ms) {
        return new Intl.DateTimeFormat("en-CA", {
            timeZone: state.zone,
            year: "numeric",
            month: "2-digit",
            day: "2-digit",
        }).format(ms);
    }

    function dayBefore(key) {
        const [year, month, day] = key.split("-").map(Number);
        return new Date(Date.UTC(year, month - 1, day - 1)).toISOString().slice(0, 10);
    }

    // "just now", "8 min ago", "2 h ago" today, then "Yesterday", then a short date, in the owner's zone.
    function ago(iso) {
        const then = Date.parse(iso);
        const now = Date.now();
        const diff = now - then;
        if (diff < MINUTE_MS) {
            return "just now";
        }
        if (diff < HOUR_MS) {
            return Math.floor(diff / MINUTE_MS) + " min ago";
        }
        const today = dateKey(now);
        const day = dateKey(then);
        if (day === today) {
            return Math.floor(diff / HOUR_MS) + " h ago";
        }
        if (day === dayBefore(today)) {
            return "Yesterday";
        }
        const options = { timeZone: state.zone, day: "numeric", month: "short" };
        if (day.slice(0, 4) !== today.slice(0, 4)) {
            options.year = "numeric";
        }
        return new Intl.DateTimeFormat(undefined, options).format(then);
    }

    function fullTime(iso) {
        return new Intl.DateTimeFormat(undefined, {
            timeZone: state.zone,
            weekday: "short",
            day: "numeric",
            month: "short",
            hour: "numeric",
            minute: "2-digit",
        }).format(Date.parse(iso));
    }

    function clockTime(iso) {
        return new Intl.DateTimeFormat(undefined, {
            timeZone: state.zone,
            hour: "numeric",
            minute: "2-digit",
        }).format(Date.parse(iso));
    }

    // Local hours 0 to 23 as "6 pm"; a range in one half of the day says it once: "6 to 7 pm".
    function hourParts(hour) {
        return [String(hour % 12 === 0 ? 12 : hour % 12), hour < 12 ? "am" : "pm"];
    }

    function hourLabel(hour) {
        return hourParts(hour).join(" ");
    }

    function hourRange(hour) {
        const [from, fromHalf] = hourParts(hour);
        const [to, toHalf] = hourParts((hour + 1) % 24);
        return fromHalf === toHalf ? from + " to " + to + " " + toHalf : from + " " + fromHalf + " to " + to + " " + toHalf;
    }

    function plural(count, one, many) {
        return count + " " + (count === 1 ? one : many);
    }

    // ---------- building elements ----------

    function make(tag, className, text) {
        const node = document.createElement(tag);
        if (className) {
            node.className = className;
        }
        if (text !== undefined && text !== null) {
            node.textContent = text;
        }
        return node;
    }

    function button(className, text, focusKey, onClick) {
        const node = make("button", className, text);
        node.type = "button";
        node.dataset.focus = focusKey;
        node.addEventListener("click", onClick);
        return node;
    }

    function icon(glyph) {
        const svg = document.createElementNS(SVG_NS, "svg");
        svg.setAttribute("viewBox", "0 0 24 24");
        svg.setAttribute("aria-hidden", "true");
        const use = document.createElementNS(SVG_NS, "use");
        use.setAttribute("href", "#i-" + glyph);
        svg.appendChild(use);
        return svg;
    }

    function timeSpan(className, iso) {
        const span = make("span", className, ago(iso));
        span.dataset.time = iso;
        return span;
    }

    function badge(status) {
        const known = BADGES[status];
        return known ? make("span", "badge " + known[0], known[1]) : null;
    }

    // ---------- today ----------

    function renderToday(today) {
        el("s-total").textContent = today.totals.length
            ? today.totals.map((t) => money(t.amountMinor, t.currency)).join(" + ")
            : state.currency
              ? money(0, state.currency)
              : "0";
        el("s-count").textContent = String(today.tipCount);
        el("s-req").textContent = String(today.requestCount);

        const busy = el("busy");
        const busiest = today.busiestHour;
        busy.hidden = !busiest || !today.hours || today.hours.length === 0;
        if (busy.hidden) {
            return;
        }
        const tips = plural(busiest.count, "tip", "tips");
        el("busy-hour").textContent = hourRange(busiest.hour);
        el("busy-sub").textContent = tips + ", your best hour today";
        busy.setAttribute("aria-label", "Busiest hour today: " + hourRange(busiest.hour) + ", " + tips);
        const most = Math.max(...today.hours.map((h) => h.count));
        const bars = el("bars");
        bars.replaceChildren(
            ...today.hours.map((h) => {
                const bar = make("span", h.start === busiest.start ? "peak" : null);
                bar.style.setProperty("--h", (most > 0 ? (h.count / most) * 100 : 0) + "%");
                return bar;
            }),
        );
        el("ax-first").textContent = hourLabel(today.hours[0].hour);
        el("ax-last").textContent = hourLabel(today.hours[today.hours.length - 1].hour);
    }

    // Today's total is zero without a tip; it still shows in the panda's currency.
    async function learnCurrency() {
        if (state.currency || state.devices.length === 0) {
            return;
        }
        const config = await getJson("/api/public/devices/" + encodeURIComponent(state.devices[0].slug));
        if (typeof config.currency === "string") {
            state.currency = config.currency;
        }
    }

    const refreshToday = coalesced(async () => {
        const today = await getJson("/api/owner/today");
        if (today.totals.length === 0) {
            await learnCurrency().catch(() => {});
        }
        renderToday(today);
    });

    // ---------- the panda's notes ----------

    function renderDigest(digest) {
        const list = el("notes-list");
        const has = digest !== null && Array.isArray(digest.bullets) && digest.bullets.length > 0;
        list.hidden = !has;
        el("notes-waiting").hidden = has;
        el("notes-sig").hidden = !has;
        if (!has) {
            list.replaceChildren();
            return;
        }
        list.replaceChildren(...digest.bullets.map((bullet) => make("li", null, bullet)));
        el("notes-meta").textContent =
            "from " +
            plural(digest.messageCount, "tipper's message", "tippers' messages") +
            " · updated " +
            clockTime(digest.generatedAt);
    }

    const refreshDigest = coalesced(async () => renderDigest(await getJson("/api/owner/digest")));

    // ---------- requests ----------

    function renderRequests(report) {
        const groups = report.groups.slice().sort((a, b) => Date.parse(b.latestAt) - Date.parse(a.latestAt));
        el("req-panel").hidden = !report.topSong && groups.length === 0;
        el("top-song").hidden = !report.topSong;
        if (report.topSong) {
            el("top-song-title").textContent = report.topSong.title;
            el("top-song-count").textContent = "· " + plural(report.topSong.count, "request", "requests");
        }
        const most = Math.max(0, ...groups.map((g) => g.count));
        el("req-list").replaceChildren(
            ...groups.map((group) => {
                const item = make("li");
                item.append(
                    make("span", "times" + (group.count > 1 && group.count === most ? " hot" : ""), "×" + group.count),
                    make("span", "what", group.label),
                    timeSpan("when", group.latestAt),
                );
                return item;
            }),
        );
    }

    const refreshRequests = coalesced(async () => renderRequests(await getJson("/api/owner/requests")));

    // ---------- devices ----------

    function renderDevices() {
        const items = el("device-items");
        if (state.devices.length === 0) {
            items.replaceChildren(make("span", "device-item", "No panda linked yet"));
            return;
        }
        const single = state.devices.length === 1;
        items.replaceChildren(
            ...state.devices.map((device) => {
                const item = make("span", "device-item");
                const name = single ? "Panda" : device.name;
                let text = name + (device.online ? " is online" : " is offline");
                if (!device.online && device.lastSeenAt) {
                    text += " · last seen " + ago(device.lastSeenAt);
                }
                item.append(make("span", "dot" + (device.online ? " on" : "")), make("span", null, text));
                return item;
            }),
        );
    }

    const refreshDevices = coalesced(async () => {
        state.devices = await getJson("/api/owner/devices");
        renderDevices();
    });

    function applyDevice(message) {
        if (typeof message.slug !== "string" || typeof message.online !== "boolean") {
            return;
        }
        const device = state.devices.find((d) => d.slug === message.slug);
        if (!device) {
            quietly(refreshDevices());
            return;
        }
        device.online = message.online;
        device.lastSeenAt = typeof message.lastSeenAt === "string" ? message.lastSeenAt : null;
        renderDevices();
    }

    // ---------- tip rows ----------

    function uiOf(id) {
        if (!state.ui.has(id)) {
            state.ui.set(id, { open: false, english: false });
        }
        return state.ui.get(id);
    }

    function refundOf(id) {
        if (!state.refunds.has(id)) {
            state.refunds.set(id, { step: "idle", amount: "" });
        }
        return state.refunds.get(id);
    }

    function kindOf(view) {
        if (view.flagged) {
            return "FLAG";
        }
        return view.messageKind || view.reaction || null;
    }

    function kindName(view) {
        const kind = kindOf(view);
        return kind ? KIND_NAMES[kind] || "Plain" : "Tip";
    }

    function messageNode(view) {
        if (view.flagged) {
            return make("span", "msg none", view.flagReason ? "Message hidden · " + view.flagReason : "Message hidden");
        }
        return view.message ? make("span", "msg", view.message) : make("span", "msg none", "No message");
    }

    function tipRow(view) {
        const ui = uiOf(view.id);
        const row = make("li", "tip" + (view.status === "REFUNDED" ? " refunded" : ""));
        row.dataset.id = view.id;
        const detailId = "detail-" + view.id;

        const head = button("tip-head", null, "head", () => {
            ui.open = !ui.open;
            rerender(view.id);
        });
        head.setAttribute("aria-expanded", String(ui.open));
        head.setAttribute("aria-controls", detailId);
        const glyph = kindOf(view) || "PLAIN";
        const kind = make("span", "kind k-" + glyph);
        kind.appendChild(icon(glyph));
        const line = make("span", "line1");
        line.append(make("span", "kname", kindName(view)));
        // Only a language other than English is worth a tag.
        if (view.languageName && view.language !== "en" && !view.flagged) {
            line.append(make("span", "lang", view.languageName));
        }
        const statusBadge = badge(view.status);
        if (statusBadge) {
            line.append(statusBadge);
        }
        head.append(
            kind,
            line,
            make("span", "amount", money(view.amountMinor, view.currency)),
            messageNode(view),
            timeSpan("time", view.createdAt),
        );
        row.append(head);

        if (view.englishText && !view.flagged) {
            row.append(translation(view, ui));
        }

        const detail = make("div", "detail");
        detail.id = detailId;
        detail.hidden = !ui.open;
        if (ui.open) {
            fillDetail(detail, view);
        }
        row.append(detail);
        return row;
    }

    function translation(view, ui) {
        const box = make("div", "trans");
        const englishId = "english-" + view.id;
        const toggle = button("translate", ui.english ? "Hide translation" : "Show in English", "translate", () => {
            ui.english = !ui.english;
            rerender(view.id);
        });
        toggle.setAttribute("aria-expanded", String(ui.english));
        toggle.setAttribute("aria-controls", englishId);
        box.append(toggle);
        if (ui.english) {
            const english = make("span", "english");
            english.id = englishId;
            english.append(
                make(
                    "small",
                    null,
                    view.languageName ? "Translated by AI from " + view.languageName : "Translated by AI",
                ),
                document.createTextNode(view.englishText),
            );
            box.append(english);
        }
        return box;
    }

    function pandaLine(view) {
        if (view.repeats === null || view.repeats === undefined) {
            return null;
        }
        if (view.flagged) {
            return "blinks ×1 (hidden messages always get the smallest reaction)";
        }
        const name = REACTION_NAMES[view.reaction] || REACTION_NAMES.PLAIN;
        return name + " ×" + view.repeats + " " + (view.repeats === 1 ? "round" : "rounds");
    }

    function fillDetail(detail, view) {
        const facts = make("dl", "facts");
        const fact = (term, value) => facts.append(make("dt", null, term), make("dd", null, value));
        fact("When", fullTime(view.createdAt));
        const panda = pandaLine(view);
        if (panda) {
            fact("Panda", panda);
        }
        fact("Status", STATUS_NAMES[view.status] || view.status);
        detail.append(facts);
        if (view.ownerNote && !view.flagged) {
            const note = make("div", "owner-note");
            note.append(make("small", null, "AI note from the message"), document.createTextNode(view.ownerNote));
            detail.append(note);
        }
        const refund = refundSlot(view);
        if (refund) {
            detail.append(refund);
        }
    }

    function hiddenRow(view) {
        const row = make("li");
        row.dataset.id = view.id;
        const first = make("div", "row1");
        first.append(
            make("span", "amt", money(view.amountMinor, view.currency)),
            make("span", "why", view.flagReason || "Hidden by the AI"),
            timeSpan("when", view.createdAt),
        );
        const second = make("div", "row2");
        second.append(make("span", "hidden-msg", "Message hidden"));
        const statusBadge = badge(view.status);
        if (statusBadge) {
            second.append(statusBadge);
        }
        row.append(first, second);
        const refund = refundSlot(view);
        if (refund) {
            const third = make("div", "row2");
            third.append(refund);
            row.append(third);
        }
        return row;
    }

    // Rebuilds a tip's rows in both lists from its current view and UI state, keeping keyboard focus.
    function rerender(id, focusKey) {
        const focused = document.activeElement;
        const focusedRow = focused && focused.closest ? focused.closest("li[data-id]") : null;
        const keep = focusKey || (focusedRow && focusedRow.dataset.id === id ? focused.dataset.focus : null);
        const keepList = focusedRow ? focusedRow.parentElement : null;

        replaceRow(el("tips"), state.tips, id, tipRow, keep, keepList);
        replaceRow(el("flag-list"), state.hidden, id, hiddenRow, keep, keepList);
    }

    function replaceRow(list, views, id, build, focusKey, focusList) {
        const old = list.querySelector("li[data-id='" + id + "']");
        const view = views.find((v) => v.id === id);
        if (!old || !view) {
            return;
        }
        const fresh = build(view);
        old.replaceWith(fresh);
        if (focusKey && (focusList === list || focusList === null)) {
            const target = fresh.querySelector("[data-focus='" + focusKey + "']");
            if (target) {
                target.focus();
            }
        }
    }

    // ---------- refunds ----------

    function refundSlot(view) {
        const refund = refundOf(view.id);
        if (refund.step === "waiting" && view.status !== "CAPTURED") {
            refund.step = view.status === "REFUNDED" ? "done" : "idle";
        }
        const amount = money(view.amountMinor, view.currency);
        const slot = make("div", "refund");
        switch (refund.step) {
            case "confirm":
            case "working": {
                const working = refund.step === "working";
                const box = make("div", "confirm");
                box.setAttribute("role", "group");
                box.setAttribute("aria-label", "Confirm refund");
                box.append(make("p", null, "Refund " + amount + " to the tipper through PayPal? This can't be undone."));
                const buttons = make("div", "btns");
                const yes = button("danger", working ? "Refunding…" : "Refund " + amount, "refund-yes", () =>
                    sendRefund(view.id),
                );
                const no = button("ghost", "Keep the tip", "refund-no", () => {
                    if (refund.step === "working") {
                        return;
                    }
                    refund.step = "idle";
                    rerender(view.id, "refund");
                });
                // aria-disabled rather than disabled, so keyboard focus stays on the button while it works.
                if (working) {
                    yes.setAttribute("aria-disabled", "true");
                    no.setAttribute("aria-disabled", "true");
                }
                buttons.append(yes, no);
                box.append(buttons);
                slot.append(box);
                return slot;
            }
            case "done":
                slot.append(make("p", "result ok", "Refunded. The tipper gets " + refund.amount + " back."));
                return slot;
            case "waiting":
                slot.append(make("p", "result wait", "Refund sent, waiting for PayPal."));
                return slot;
            case "gone":
                slot.append(make("p", "result err", "This tip can't be refunded any more."));
                return slot;
            case "error": {
                const buttons = make("div", "btns");
                buttons.append(
                    make("p", "result err", "The refund didn't go through. PayPal may be busy."),
                    button("ghost", "Try again", "refund-retry", () => sendRefund(view.id)),
                );
                slot.append(buttons);
                return slot;
            }
            default:
                if (view.status !== "CAPTURED") {
                    return null;
                }
                slot.append(
                    button("refund-btn", "Refund " + amount, "refund", () => {
                        refund.step = "confirm";
                        rerender(view.id, "refund-no");
                    }),
                );
                return slot;
        }
    }

    async function sendRefund(id) {
        const refund = refundOf(id);
        if (refund.step === "working") {
            return;
        }
        const view = state.tips.find((v) => v.id === id) || state.hidden.find((v) => v.id === id);
        if (!view) {
            return;
        }
        refund.step = "working";
        refund.amount = money(view.amountMinor, view.currency);
        rerender(id, "refund-yes");
        let next = "error";
        try {
            const headers = {};
            headers[csrf.header] = csrf.token;
            const response = await request("/api/owner/tips/" + encodeURIComponent(id) + "/refund", {
                method: "POST",
                headers,
            });
            if (response.status === 200) {
                next = "done";
            } else if (response.status === 202) {
                next = "waiting";
            } else if (response.status === 409 || response.status === 404) {
                next = "gone";
            }
        } catch (e) {
            if (e instanceof SignedOut) {
                return;
            }
        }
        refund.step = next;
        rerender(id, next === "error" ? "refund-retry" : null);
        quietly(refreshTip(id));
        quietly(refreshToday());
    }

    // ---------- the tip list ----------

    function matchesFilter(view) {
        if (state.filter === "ALL") {
            return true;
        }
        if (state.filter === "HIDDEN") {
            return view.flagged;
        }
        return !view.flagged && view.messageKind === state.filter;
    }

    function tipsQuery(page, size) {
        return "/api/owner/tips?page=" + page + "&size=" + size + FILTERS[state.filter];
    }

    function renderTipsMeta() {
        const empty = state.tips.length === 0;
        el("tips-count").textContent = state.total > 0 ? state.total + " in total" : "";
        el("chips").hidden = empty && state.filter === "ALL";
        const copy = EMPTY[state.filter] || EMPTY.KIND;
        el("tips-empty").hidden = !empty;
        el("tips-empty-title").textContent = copy[0];
        el("tips-empty-text").textContent = copy[1];
        el("more").hidden = state.tips.length >= state.total;
    }

    function renderTips() {
        el("tips").replaceChildren(...state.tips.map(tipRow));
        renderTipsMeta();
    }

    async function loadTips() {
        const generation = ++state.generation;
        el("tips-error").hidden = true;
        el("tips-loading").hidden = false;
        try {
            const page = await getJson(tipsQuery(0, PAGE_SIZE));
            if (generation !== state.generation) {
                return;
            }
            state.tips = page.items;
            state.page = 0;
            state.total = page.totalItems;
            noteCurrency(page.items);
            renderTips();
        } catch (e) {
            if (generation === state.generation && !(e instanceof SignedOut)) {
                el("tips-error").hidden = false;
            }
            throw e;
        } finally {
            if (generation === state.generation) {
                el("tips-loading").hidden = true;
            }
        }
    }

    async function loadOlder() {
        const generation = state.generation;
        const more = el("more");
        more.disabled = true;
        more.textContent = "Loading…";
        try {
            const page = await getJson(tipsQuery(state.page + 1, PAGE_SIZE));
            if (generation !== state.generation) {
                return;
            }
            const known = new Set(state.tips.map((t) => t.id));
            const fresh = page.items.filter((t) => !known.has(t.id));
            state.tips.push(...fresh);
            state.page = page.page;
            state.total = page.totalItems;
            el("tips").append(...fresh.map(tipRow));
            if (page.items.length < PAGE_SIZE) {
                state.total = state.tips.length;
            }
            renderTipsMeta();
        } catch (e) {
            if (!(e instanceof SignedOut)) {
                el("tips-error").hidden = false;
            }
        } finally {
            more.disabled = false;
            more.textContent = "Show older tips";
        }
    }

    function noteCurrency(items) {
        if (!state.currency && items.length > 0) {
            state.currency = items[0].currency;
        }
    }

    // A tip newer than the top of the list goes on top; one already shown updates in place.
    function applyTip(view, announce) {
        const index = state.tips.findIndex((t) => t.id === view.id);
        if (index >= 0) {
            if (matchesFilter(view)) {
                state.tips[index] = view;
                rerender(view.id);
            } else {
                removeFromList(view.id);
            }
            return;
        }
        if (!matchesFilter(view)) {
            return;
        }
        const top = state.tips[0];
        if (top && Date.parse(view.createdAt) < Date.parse(top.createdAt)) {
            return;
        }
        state.tips.unshift(view);
        state.total += 1;
        noteCurrency([view]);
        const row = tipRow(view);
        row.classList.add("new");
        row.addEventListener("animationend", () => row.classList.remove("new"), { once: true });
        el("tips").prepend(row);
        renderTipsMeta();
        if (announce) {
            toast(view);
        }
    }

    function removeFromList(id) {
        const index = state.tips.findIndex((t) => t.id === id);
        if (index < 0) {
            return;
        }
        state.tips.splice(index, 1);
        state.total = Math.max(0, state.total - 1);
        const row = el("tips").querySelector("li[data-id='" + id + "']");
        if (row) {
            row.remove();
        }
        renderTipsMeta();
    }

    async function loadTip(id) {
        const response = await request("/api/owner/tips/" + encodeURIComponent(id));
        const inHidden = state.hidden.some((t) => t.id === id);
        if (response.status === 404) {
            removeFromList(id);
            if (inHidden) {
                quietly(refreshHidden());
            }
            return;
        }
        if (!response.ok) {
            throw new HttpError(response.status);
        }
        const view = await response.json();
        applyTip(view, true);
        if (view.flagged || inHidden) {
            quietly(refreshHidden());
        }
    }

    const tipRefreshers = new Map();

    function refreshTip(id) {
        if (!tipRefreshers.has(id)) {
            tipRefreshers.set(
                id,
                coalesced(() => loadTip(id)),
            );
        }
        return tipRefreshers.get(id)();
    }

    // The first page again, merged into what is shown, so a page that missed events catches up.
    const refreshFirstPage = coalesced(async () => {
        const generation = state.generation;
        const page = await getJson(tipsQuery(0, PAGE_SIZE));
        if (generation !== state.generation) {
            return;
        }
        page.items
            .slice()
            .reverse()
            .forEach((view) => applyTip(view, true));
        state.total = page.totalItems;
        renderTipsMeta();
    });

    function chooseFilter(filter) {
        if (!(filter in FILTERS) || filter === state.filter) {
            return;
        }
        state.filter = filter;
        document.querySelectorAll("#chips .chip").forEach((chip) => {
            chip.setAttribute("aria-pressed", String(chip.dataset.filter === filter));
        });
        state.tips = [];
        state.total = 0;
        el("tips").replaceChildren();
        el("tips-empty").hidden = true;
        el("more").hidden = true;
        quietly(loadTips());
    }

    // ---------- hidden messages ----------

    function renderHidden() {
        const list = el("flag-list");
        const focused = document.activeElement;
        const row = list.contains(focused) ? focused.closest("li[data-id]") : null;
        el("flag-panel").hidden = state.hiddenTotal === 0;
        el("flag-count").textContent = String(state.hiddenTotal);
        list.replaceChildren(...state.hidden.map(hiddenRow));
        if (row) {
            const target = list.querySelector("li[data-id='" + row.dataset.id + "'] [data-focus='" + focused.dataset.focus + "']");
            if (target) {
                target.focus();
            }
        }
    }

    const refreshHidden = coalesced(async () => {
        const page = await getJson("/api/owner/tips?flagged=true&page=0&size=" + HIDDEN_PANEL_SIZE);
        state.hidden = page.items;
        state.hiddenTotal = page.totalItems;
        renderHidden();
    });

    // ---------- toast ----------

    function toast(view) {
        const box = el("toast");
        const kind = kindOf(view);
        box.hidden = false;
        el("toast-text").textContent =
            "New tip, " + money(view.amountMinor, view.currency) + (kind ? " · " + KIND_NAMES[kind] : "");
        clearTimeout(state.toastTimer);
        state.toastTimer = setTimeout(() => {
            box.hidden = true;
        }, TOAST_MS);
    }

    // ---------- live channel ----------

    function setLive(live) {
        el("live-tag").hidden = !live;
    }

    function reconnectDelay() {
        const seconds = BACKOFF_SECONDS[Math.min(state.attempt, BACKOFF_SECONDS.length - 1)];
        state.attempt += 1;
        return seconds * 1000 * (1 - Math.random() * JITTER);
    }

    function startPolling() {
        if (state.pollTimer !== null) {
            return;
        }
        const poll = () => {
            quietly(refreshToday());
            quietly(refreshFirstPage());
        };
        poll();
        state.pollTimer = setInterval(poll, POLL_MS);
    }

    function stopPolling() {
        clearInterval(state.pollTimer);
        state.pollTimer = null;
    }

    function catchUp() {
        quietly(refreshToday());
        quietly(refreshDigest());
        quietly(refreshRequests());
        quietly(refreshDevices());
        quietly(refreshHidden());
        quietly(refreshFirstPage());
    }

    function handle(data, closing) {
        let message;
        try {
            message = JSON.parse(data);
        } catch (e) {
            return;
        }
        if (!message || typeof message.type !== "string") {
            return;
        }
        switch (message.type) {
            case "tip":
                if (typeof message.id === "string" && UUID.test(message.id)) {
                    quietly(refreshTip(message.id));
                    quietly(refreshRequests());
                }
                break;
            case "today":
                quietly(refreshToday());
                break;
            case "digest":
                quietly(refreshDigest());
                quietly(refreshRequests());
                break;
            case "device":
                applyDevice(message);
                break;
            case "closing":
                closing.reason = message.reason;
                break;
            default:
                break;
        }
    }

    function connect() {
        state.reconnectTimer = null;
        if (state.leaving || state.replaced) {
            return;
        }
        const scheme = window.location.protocol === "https:" ? "wss:" : "ws:";
        const socket = new WebSocket(scheme + "//" + window.location.host + "/ws/owner");
        const closing = { reason: null };
        state.socket = socket;
        socket.addEventListener("open", () => {
            state.openedAt = performance.now();
            setLive(true);
            stopPolling();
            if (state.disconnected) {
                state.disconnected = false;
                catchUp();
            }
        });
        socket.addEventListener("message", (event) => handle(event.data, closing));
        socket.addEventListener("close", () => {
            if (state.socket !== socket) {
                return;
            }
            state.socket = null;
            setLive(false);
            if (state.openedAt !== null && performance.now() - state.openedAt >= STABLE_MS) {
                state.attempt = 0;
            }
            state.openedAt = null;
            state.disconnected = true;
            if (closing.reason === "signed_out" || closing.reason === "session_expired") {
                toLogin();
                return;
            }
            // A refused handshake looks like any other close; the poll's 401 sends a signed out page away.
            startPolling();
            if (closing.reason === "replaced") {
                state.replaced = true;
                el("replaced-note").hidden = false;
                return;
            }
            state.reconnectTimer = setTimeout(connect, reconnectDelay());
        });
    }

    function resume() {
        if (!state.replaced || document.visibilityState !== "visible") {
            return;
        }
        state.replaced = false;
        state.attempt = 0;
        el("replaced-note").hidden = true;
        connect();
    }

    // ---------- start ----------

    async function loadMe() {
        const me = await getJson("/api/owner/me");
        try {
            new Intl.DateTimeFormat(undefined, { timeZone: me.timeZone });
            state.zone = me.timeZone;
        } catch (e) {
            state.zone = undefined;
        }
        el("who-name").textContent = me.displayName;
        el("who").hidden = false;
    }

    const LOADERS = [refreshToday, refreshDigest, refreshRequests, refreshHidden, loadTips];

    async function load(loaders) {
        el("load-error").hidden = true;
        el("loading").hidden = false;
        const results = await Promise.allSettled(loaders.map((loader) => loader()));
        el("loading").hidden = true;
        if (state.leaving) {
            return [];
        }
        const failed = loaders.filter((loader, i) => results[i].status === "rejected");
        el("load-error").hidden = failed.length === 0;
        return failed;
    }

    let retry = [];

    async function start() {
        try {
            // The zone formats every time and the devices name today's currency, so they come first.
            await Promise.all([loadMe(), refreshDevices()]);
        } catch (e) {
            if (e instanceof SignedOut) {
                return;
            }
            retry = null;
            el("loading").hidden = true;
            el("load-error").hidden = false;
            return;
        }
        retry = await load(LOADERS);
        if (!state.leaving && state.socket === null && state.reconnectTimer === null) {
            connect();
        }
    }

    document.addEventListener("DOMContentLoaded", () => {
        el("load-retry").addEventListener("click", async () => {
            if (retry === null) {
                await start();
                return;
            }
            retry = await load(retry);
        });
        el("tips-retry").addEventListener("click", () => quietly(loadTips()));
        el("more").addEventListener("click", loadOlder);
        document.querySelectorAll("#chips .chip").forEach((chip) => {
            chip.addEventListener("click", () => chooseFilter(chip.dataset.filter));
        });
        window.addEventListener("focus", resume);
        document.addEventListener("visibilitychange", resume);
        setInterval(() => {
            document.querySelectorAll("[data-time]").forEach((node) => {
                node.textContent = ago(node.dataset.time);
            });
            renderDevices();
        }, TICK_MS);
        start();
    });
})();
