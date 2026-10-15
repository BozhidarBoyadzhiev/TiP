"use strict";

// The owner dashboard. Loads everything from /api/owner/*, then listens on /ws/owner (docs/OWNER_CHANNEL.md)
// and refetches what changed. Every value from the server is untrusted and goes into the page with
// textContent or createTextNode; elements are built with createElement only. The same page serves a judge's
// sandbox (its strip, sample tips marked Demo, Continue on another device, Test payment details) and the read
// only public sample, which opens no socket, never polls and shows its write buttons switched off.
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
    const NOTES_WAITING = "The panda will write notes once a few people leave messages today.";
    const NOTES_REWRITING = "The panda is rewriting its notes.";
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

    const REAL_PANDA = document.querySelector("meta[name='real-panda']").content;
    const COPIED_MS = 2000;
    const LINK_MS = 600000;
    const QR_QUIET = 2;
    const QR_LIGHT = "#ecd7b1";
    const QR_DARK = "#1b0f08";

    const state = {
        zone: undefined,
        // OWNER, SANDBOX or PUBLIC_DEMO, from /me.
        kind: "OWNER",
        readOnly: false,
        sheet: { timer: null, expiresAt: 0, opener: null, working: false },
        pay: { opener: null },
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
        // Hidden messages read on demand, in memory only, and verdicts in flight. Rows unhidden in this tab
        // stay in the hidden panel until the page is reloaded, so the owner can take it back.
        reveals: new Map(),
        verdicts: new Map(),
        kept: new Set(),
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

    // ?embed: the dashboard sits in the side by side view's frame (/owner/live). It only changes what is
    // shown, and leaving the dashboard leaves the whole view, not just the frame.
    const EMBEDDED = new URLSearchParams(window.location.search).has("embed");
    if (EMBEDDED) {
        document.documentElement.classList.add("embedded");
    }
    const LEAVE = EMBEDDED ? window.top.location : window.location;

    class SignedOut extends Error {}

    class HttpError extends Error {
        constructor(status) {
            super("HTTP " + status);
            this.status = status;
        }
    }

    // A sandbox signs in again with its code, the sample opens again from its link, an owner signs in.
    function toLogin(sandboxExpired) {
        if (state.leaving) {
            return;
        }
        state.leaving = true;
        if (sandboxExpired) {
            LEAVE.assign("/judges?sandboxExpired");
        } else if (state.kind === "SANDBOX") {
            LEAVE.assign("/judges");
        } else if (state.readOnly) {
            LEAVE.assign("/demo");
        } else {
            LEAVE.assign("/owner/login");
        }
    }

    async function problemCode(response) {
        try {
            const body = await response.json();
            return body && typeof body.code === "string" ? body.code : null;
        } catch (e) {
            return null;
        }
    }

    async function request(path, options) {
        const response = await fetch(path, {
            credentials: "same-origin",
            cache: "no-store",
            method: (options && options.method) || "GET",
            headers: Object.assign({ Accept: "application/json" }, options && options.headers),
            body: options && options.body,
        });
        if (response.status === 401) {
            toLogin((await problemCode(response)) === "SANDBOX_EXPIRED");
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

    // Every time the page shows is formatted here: the browser's locale, the owner's time zone from /me, so
    // rows, notes, requests and the status line agree with the busiest hour, which the server counts in it.
    function ownerTime(options, locale) {
        return new Intl.DateTimeFormat(locale, Object.assign({}, options, { timeZone: state.zone }));
    }

    function dateKey(ms) {
        return ownerTime({ year: "numeric", month: "2-digit", day: "2-digit" }, "en-CA").format(ms);
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
        const options = { day: "numeric", month: "short" };
        if (day.slice(0, 4) !== today.slice(0, 4)) {
            options.year = "numeric";
        }
        return ownerTime(options).format(then);
    }

    function fullTime(iso) {
        return ownerTime({ weekday: "short", day: "numeric", month: "short", hour: "numeric", minute: "2-digit" }).format(
            Date.parse(iso),
        );
    }

    function clockTime(iso) {
        return ownerTime({ hour: "numeric", minute: "2-digit" }).format(Date.parse(iso));
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
        const total = el("s-total");
        const unknown = today.totals.length === 0 && !state.currency;
        total.classList.toggle("quiet", unknown);
        total.textContent = today.totals.length
            ? today.totals.map((t) => money(t.amountMinor, t.currency)).join(" + ")
            : unknown
              ? "No tips yet today"
              : money(0, state.currency);
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

    // The resting strip is read again with today's numbers, so it comes and goes without a reload.
    const refreshToday = coalesced(async () => {
        quietly(refreshResting());
        const today = await getJson("/api/owner/today");
        if (today.totals.length === 0) {
            await learnCurrency().catch(() => {});
        }
        renderToday(today);
    });

    async function refreshResting() {
        const me = await getJson("/api/owner/me");
        el("resting").hidden = me.aiResting !== true;
    }

    // ---------- the panda's notes ----------

    // null: no notes yet. {status: "rewriting"}: the notes were dropped when the owner hid a message again
    // and the panda writes them again from the messages still shown, so the card stays, without bullets.
    function renderDigest(digest) {
        const list = el("notes-list");
        const has = digest !== null && Array.isArray(digest.bullets) && digest.bullets.length > 0;
        const rewriting = !has && digest !== null && digest.status === "rewriting";
        list.hidden = !has;
        el("notes").classList.toggle("no-notes", !has && !rewriting);
        el("notes-waiting").hidden = has;
        el("notes-waiting").textContent = rewriting ? NOTES_REWRITING : NOTES_WAITING;
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

    function tipPagePath(slug) {
        return "/t/" + encodeURIComponent(slug);
    }

    // The sample is invented data: no panda to report on and nothing live.
    function renderSampleDevice() {
        const item = make("span", "device-item");
        item.append(make("span", "dot"), make("span", null, "Sample data"));
        el("device-items").replaceChildren(item);
        el("live-tag").textContent = "· not live";
        el("live-tag").hidden = false;
    }

    // A sandbox panda is virtual: it plays on its own page whenever that page is open, so it counts as online.
    function renderSandboxDevice() {
        const device = state.devices[0];
        if (!device) {
            el("device-items").replaceChildren(make("span", "device-item", "No panda linked yet"));
            el("watch").hidden = true;
            return;
        }
        const item = make("span", "device-item");
        item.append(make("span", "dot on"), make("span", null, "Your panda is online"));
        el("device-items").replaceChildren(item);
        el("watch").href = "/p/" + encodeURIComponent(device.slug);
        el("watch").hidden = false;
        el("sandbox-tip").href = tipPagePath(device.slug);
    }

    function renderDevices() {
        if (state.readOnly) {
            renderSampleDevice();
            return;
        }
        if (state.kind === "SANDBOX") {
            renderSandboxDevice();
            return;
        }
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
            state.ui.set(id, { open: false, english: false, panelEnglish: false });
        }
        return state.ui.get(id);
    }

    function refundOf(id) {
        if (!state.refunds.has(id)) {
            state.refunds.set(id, { step: "idle", amount: "" });
        }
        return state.refunds.get(id);
    }

    function revealOf(id) {
        if (!state.reveals.has(id)) {
            state.reveals.set(id, { step: "idle", text: "", language: null, languageName: null, englishText: null });
        }
        return state.reveals.get(id);
    }

    function verdictOf(id) {
        if (!state.verdicts.has(id)) {
            state.verdicts.set(id, { step: "idle", hidden: false });
        }
        return state.verdicts.get(id);
    }

    function revealedText(view) {
        const reveal = state.reveals.get(view.id);
        return view.flagged && reveal && reveal.step === "shown" ? reveal.text : null;
    }

    // The language and translation of a message the owner can read: the tip's own, or a revealed one's.
    function readOf(view) {
        if (!view.flagged) {
            return view;
        }
        const reveal = state.reveals.get(view.id);
        if (!reveal || reveal.step !== "shown") {
            return null;
        }
        return {
            id: view.id,
            language: reveal.language,
            languageName: reveal.languageName,
            englishText: reveal.englishText,
        };
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
        const revealed = revealedText(view);
        if (revealed !== null) {
            return make("span", "msg", revealed);
        }
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
        const read = readOf(view);
        // Only a language other than English is worth a tag.
        if (read && read.languageName && read.language !== "en") {
            line.append(make("span", "lang", read.languageName));
        }
        if (view.unhidden) {
            line.append(make("span", "unhid", "Unhidden by you"));
        }
        const statusBadge = badge(view.status);
        if (statusBadge) {
            line.append(statusBadge);
        }
        if (unread(view)) {
            line.append(make("span", "badge unread", "Not read"));
        }
        if (demoBadge(view)) {
            line.append(make("span", "badge demo", "Demo"));
        }
        head.append(
            kind,
            line,
            make("span", "amount", money(view.amountMinor, view.currency)),
            messageNode(view),
            timeSpan("time", view.createdAt),
        );
        row.append(head);

        if (read && read.englishText) {
            row.append(translation(read, ui, "english"));
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

    // flag is "english" for the tip list and "panelEnglish" for the hidden panel, so each has its own toggle.
    function translation(view, ui, flag) {
        const box = make("div", "trans");
        const englishId = (flag === "english" ? "english-" : "flag-english-") + view.id;
        const toggle = button("translate", ui[flag] ? "Hide translation" : "Show in English", "translate", () => {
            ui[flag] = !ui[flag];
            rerender(view.id);
        });
        toggle.setAttribute("aria-expanded", String(ui[flag]));
        toggle.setAttribute("aria-controls", englishId);
        box.append(toggle);
        if (ui[flag]) {
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

    // The model could not be reached, so the panda blinked once without reading the message.
    function unread(view) {
        return view.aiStatus === "UNAVAILABLE";
    }

    // The sample is all invented, so only a sandbox (or a dev run) needs telling which tips are samples.
    function demoBadge(view) {
        return view.demo && !state.readOnly;
    }

    function pandaLine(view) {
        if (view.repeats === null || view.repeats === undefined) {
            return null;
        }
        if (unread(view) && !view.flagged) {
            return "blinks ×1";
        }
        if (view.flagged) {
            return "blinks ×1 (hidden messages always get the smallest reaction)";
        }
        if (view.unhidden) {
            return "blinks ×1 (it arrived hidden, so it got the smallest reaction)";
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
        if (unread(view)) {
            const note = make("div", "owner-note");
            note.append(
                make("small", null, "Why a single blink"),
                document.createTextNode(
                    "The panda couldn't read this message just then, so it gave the smallest reaction. The tip itself is fine.",
                ),
            );
            detail.append(note);
        }
        if (view.ownerNote && !view.flagged) {
            const note = make("div", "owner-note");
            note.append(make("small", null, "AI note from the message"), document.createTextNode(view.ownerNote));
            detail.append(note);
        }
        if (state.readOnly) {
            const actions = readOnlyActions(view, true);
            if (actions) {
                detail.append(actions);
            }
            return;
        }
        const refund = refundSlot(view);
        if (view.flagged || view.unhidden) {
            // Side by side with the refund, as in the hidden panel.
            const actions = make("div", "actions");
            actions.append(moderationSlot(view));
            if (refund) {
                actions.append(refund);
            }
            detail.append(actions);
        } else if (refund) {
            detail.append(refund);
        }
        if (view.demo && view.status === "CAPTURED") {
            detail.append(demoNote(view));
        }
    }

    // A sample tip was never paid, so it cannot be refunded; a real one sent to the panda can.
    function demoNote(view) {
        const note = make("div", "owner-note demo-note");
        const link = make("a", "ghost demo-tip", "Tip your panda");
        link.href = tipPagePath(view.deviceSlug);
        note.append(make("span", null, "Demo tip. Send a real tip to try refunds."), link);
        return note;
    }

    function lock() {
        const node = make("span", "ro");
        node.append(icon("LOCK"), document.createTextNode("Read only"));
        return node;
    }

    function disabledButton(className, text) {
        const node = make("button", className, text);
        node.type = "button";
        node.disabled = true;
        return node;
    }

    // The public sample shows what an owner could do, switched off. Reading a hidden message is a read and works;
    // Hide again only folds it away in this page. In a tip's detail the hidden message controls come first, as in
    // the hidden panel.
    function readOnlyActions(view, inDetail) {
        const row = make("div", "ro-row moderate");
        let locked = false;
        if (view.flagged) {
            const reveal = revealOf(view.id);
            if (reveal.step === "shown") {
                row.append(
                    button("ghost", "Hide again", "reveal", () => {
                        reveal.step = "idle";
                        uiOf(view.id).english = false;
                        uiOf(view.id).panelEnglish = false;
                        rerender(view.id, "reveal");
                    }),
                    disabledButton("ghost", "Not abusive"),
                );
                locked = true;
            } else {
                row.append(moderationSlot(view));
            }
        } else if (view.unhidden) {
            row.append(disabledButton("ghost", "Hide again"));
            locked = true;
        }
        const revealed = view.flagged && revealOf(view.id).step === "shown";
        if (view.status === "CAPTURED" && !revealed && (inDetail || view.flagged)) {
            row.append(disabledButton("refund-btn", "Refund " + money(view.amountMinor, view.currency)));
            locked = true;
        }
        if (locked) {
            row.append(lock());
        }
        return row.childElementCount > 0 ? row : null;
    }

    // A row the owner unhid in this tab stays in the panel, saying so, with the way back.
    function hiddenRow(view) {
        const row = make("li");
        row.dataset.id = view.id;
        const first = make("div", "row1");
        first.append(
            make("span", "amt", money(view.amountMinor, view.currency)),
            make("span", "why", view.flagged ? view.flagReason || "Hidden by the AI" : "Unhidden by you"),
            timeSpan("when", view.createdAt),
        );
        const second = make("div", "row2");
        const revealed = revealedText(view);
        if (!view.flagged) {
            second.append(make("p", "result ok", "Not abusive. It's with your tips now."));
        } else if (revealed !== null) {
            const text = make("div", "revealed");
            text.append(make("small", null, "What they wrote"), document.createTextNode(revealed));
            const read = readOf(view);
            if (read.englishText) {
                text.append(translation(read, uiOf(view.id), "panelEnglish"));
            }
            second.append(text);
        } else {
            second.append(make("span", "hidden-msg", "Message hidden"));
        }
        const statusBadge = badge(view.status);
        if (statusBadge) {
            second.append(statusBadge);
        }
        if (demoBadge(view)) {
            second.append(make("span", "badge demo", "Demo"));
        }
        row.append(first, second);
        const third = make("div", "row2");
        if (state.readOnly) {
            const actions = readOnlyActions(view, false);
            if (actions) {
                third.append(actions);
            }
            row.append(third);
            return row;
        }
        third.append(moderationSlot(view));
        const refund = view.flagged ? refundSlot(view) : null;
        if (refund) {
            third.append(refund);
        }
        row.append(third);
        return row;
    }

    // ---------- reading and overruling hidden messages ----------

    // Show message, then Not abusive; Hide again once unhidden. Each keeps focus in its place, like refund.
    function moderationSlot(view) {
        const verdict = verdictOf(view.id);
        const reveal = revealOf(view.id);
        const slot = make("div", "btns moderate");
        if (verdict.step === "error") {
            slot.append(
                make("p", "result err", verdict.hidden ? "Couldn't hide it again." : "Couldn't unhide it."),
                button("ghost", "Try again", "verdict-retry", () => sendVerdict(view.id, verdict.hidden)),
            );
            return slot;
        }
        if (!view.flagged) {
            slot.append(verdictButton(view, "Hide again", true));
            return slot;
        }
        switch (reveal.step) {
            case "shown":
                slot.append(verdictButton(view, "Not abusive", false));
                return slot;
            case "working": {
                const loading = button("ghost", "Loading the message…", "reveal", () => {});
                loading.setAttribute("aria-disabled", "true");
                slot.append(loading);
                return slot;
            }
            case "error":
                slot.append(
                    make("p", "result err", "The message didn't load."),
                    button("ghost", "Try again", "reveal-retry", () => loadMessage(view.id)),
                );
                return slot;
            default:
                slot.append(button("ghost", "Show message", "reveal", () => loadMessage(view.id)));
                return slot;
        }
    }

    function verdictButton(view, text, hidden) {
        const working = verdictOf(view.id).step === "working";
        const node = button("ghost", working ? "Saving…" : text, "verdict", () => sendVerdict(view.id, hidden));
        // aria-disabled rather than disabled, so keyboard focus stays on the button while it works.
        if (working) {
            node.setAttribute("aria-disabled", "true");
        }
        return node;
    }

    // The text is fetched each time it is asked for and kept in memory only, never stored by the browser.
    async function loadMessage(id) {
        const reveal = revealOf(id);
        if (reveal.step === "working") {
            return;
        }
        reveal.step = "working";
        rerender(id, "reveal");
        let next = "error";
        try {
            const response = await request("/api/owner/tips/" + encodeURIComponent(id) + "/message");
            if (response.ok) {
                const body = await response.json();
                if (body && typeof body.text === "string") {
                    reveal.text = body.text;
                    reveal.language = typeof body.language === "string" ? body.language : null;
                    reveal.languageName = typeof body.languageName === "string" ? body.languageName : null;
                    reveal.englishText = typeof body.englishText === "string" ? body.englishText : null;
                    next = "shown";
                }
            } else if (response.status === 404) {
                next = "idle";
                quietly(refreshTip(id));
                quietly(refreshHidden());
            }
        } catch (e) {
            if (e instanceof SignedOut) {
                return;
            }
        }
        reveal.step = next;
        const shownFocus = state.readOnly ? "reveal" : "verdict";
        rerender(id, next === "shown" ? shownFocus : next === "error" ? "reveal-retry" : "reveal");
    }

    async function sendVerdict(id, hidden) {
        const verdict = verdictOf(id);
        if (verdict.step === "working") {
            return;
        }
        verdict.step = "working";
        verdict.hidden = hidden;
        rerender(id, "verdict");
        let view = null;
        let gone = false;
        try {
            const headers = { "Content-Type": "application/json" };
            headers[csrf.header] = csrf.token;
            const response = await request("/api/owner/tips/" + encodeURIComponent(id) + "/verdict", {
                method: "PUT",
                headers,
                body: JSON.stringify({ hidden }),
            });
            if (response.ok) {
                view = await response.json();
            } else if (response.status === 404) {
                gone = true;
            }
        } catch (e) {
            if (e instanceof SignedOut) {
                return;
            }
        }
        if (gone) {
            verdict.step = "idle";
            quietly(refreshTip(id));
            quietly(refreshHidden());
            return;
        }
        if (!view) {
            verdict.step = "error";
            rerender(id, "verdict-retry");
            return;
        }
        verdict.step = "idle";
        const inPanel = state.hidden.findIndex((v) => v.id === id);
        if (view.flagged) {
            state.reveals.delete(id);
            state.kept.delete(id);
            const ui = uiOf(id);
            ui.english = false;
            ui.panelEnglish = false;
        } else if (inPanel >= 0) {
            state.kept.add(id);
        }
        if (inPanel >= 0) {
            state.hidden[inPanel] = view;
        }
        applyTip(view, false);
        rerender(id, view.flagged ? "reveal" : "verdict");
        quietly(refreshHidden());
        quietly(refreshToday());
        quietly(refreshRequests());
        quietly(refreshDigest());
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
        if (view.demo) {
            return null;
        }
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
            insertOlder(view);
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

    // An older tip that now matches, such as one the owner unhid, goes in its place among the loaded ones,
    // quietly; one that belongs on a page not loaded yet comes with that page.
    function insertOlder(view) {
        const at = state.tips.findIndex((t) => Date.parse(t.createdAt) < Date.parse(view.createdAt));
        if (at < 0 && state.tips.length < state.total) {
            return;
        }
        const index = at < 0 ? state.tips.length : at;
        const next = state.tips[index];
        state.tips.splice(index, 0, view);
        state.total += 1;
        const list = el("tips");
        list.insertBefore(tipRow(view), next ? list.querySelector("li[data-id='" + next.id + "']") : null);
        renderTipsMeta();
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
            // A row that leaves the filter while focused hands focus to the filter it left.
            const hadFocus = row.contains(document.activeElement);
            row.remove();
            if (hadFocus) {
                const chip = document.querySelector("#chips .chip[aria-pressed='true']");
                if (chip) {
                    chip.focus();
                }
            }
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
        el("flag-panel").hidden = state.hidden.length === 0;
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
        const fresh = new Set(page.items.map((t) => t.id));
        const kept = state.hidden
            .filter((v) => state.kept.has(v.id) && !fresh.has(v.id))
            .map((v) => state.tips.find((t) => t.id === v.id) || v)
            .filter((v) => !v.flagged);
        state.hidden = page.items.concat(kept);
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
                toLogin(false);
                return;
            }
            if (closing.reason === "sandbox_expired") {
                toLogin(true);
                return;
            }
            // The server's guard for a read only session the page did not know about: no socket, no polling.
            if (closing.reason === "read_only") {
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
        state.kind = typeof me.kind === "string" ? me.kind : "OWNER";
        state.readOnly = me.readOnly === true;
        el("resting").hidden = me.aiResting !== true;
        if (state.readOnly) {
            el("who-label").textContent = "Looking at";
            el("who-name").textContent = "A sample owner";
            el("sign-out").hidden = true;
            el("tip-real-panda").href = REAL_PANDA;
            el("tip-real-panda").hidden = !REAL_PANDA;
            el("sample-strip").hidden = false;
        } else {
            el("who-name").textContent = me.displayName;
        }
        if (state.kind === "SANDBOX" && typeof me.expiresAt === "string") {
            el("sandbox-until").textContent = fullTime(me.expiresAt);
            el("sandbox-strip").hidden = false;
            quietly(loadTestPayment());
        }
        el("who").hidden = false;
    }

    // ---------- continue on another device ----------

    function drawQr(qr) {
        const canvas = el("qr");
        const side = qr.size + 2 * QR_QUIET;
        canvas.width = side;
        canvas.height = side;
        const g = canvas.getContext("2d");
        g.fillStyle = QR_LIGHT;
        g.fillRect(0, 0, side, side);
        g.fillStyle = QR_DARK;
        qr.rows.forEach((row, y) => {
            for (let x = 0; x < row.length; x++) {
                if (row.charAt(x) === "1") {
                    g.fillRect(x + QR_QUIET, y + QR_QUIET, 1, 1);
                }
            }
        });
    }

    function countdownButton(text, onClick) {
        const node = make("button", null, text);
        node.type = "button";
        node.addEventListener("click", onClick);
        return node;
    }

    function tickSheet() {
        const left = Math.max(0, Math.ceil((state.sheet.expiresAt - Date.now()) / 1000));
        const countdown = el("countdown");
        if (left <= 0) {
            clearInterval(state.sheet.timer);
            state.sheet.timer = null;
            el("qr-card").classList.add("dead");
            el("resume-url").value = "This link has run out";
            el("copy-link").disabled = true;
            countdown.replaceChildren(
                document.createTextNode("This link has run out. "),
                countdownButton("Make a new one", makeLink),
            );
            return;
        }
        const time = make("strong", null, Math.floor(left / 60) + ":" + String(left % 60).padStart(2, "0"));
        countdown.replaceChildren(document.createTextNode("Works for "), time);
    }

    function sheetFailed(message) {
        el("qr-card").classList.add("dead");
        el("resume-url").value = "";
        el("copy-link").disabled = true;
        el("countdown").replaceChildren(document.createTextNode(message + " "), countdownButton("Try again", makeLink));
    }

    // A new one time link each time: the server makes the token and its QR code; the countdown follows the
    // server's clock through its Date header, so a phone with a wrong clock still counts right.
    async function makeLink() {
        if (state.sheet.working) {
            return;
        }
        state.sheet.working = true;
        clearInterval(state.sheet.timer);
        state.sheet.timer = null;
        el("countdown").replaceChildren(document.createTextNode("Making a link…"));
        try {
            const headers = {};
            headers[csrf.header] = csrf.token;
            const response = await request("/api/owner/resume-link", { method: "POST", headers });
            if (response.status === 429) {
                sheetFailed("You've made a few links just now. Wait a few minutes.");
                return;
            }
            if (!response.ok) {
                sheetFailed("The link didn't load.");
                return;
            }
            const link = await response.json();
            const serverNow = Date.parse(response.headers.get("Date"));
            const skew = Number.isNaN(serverNow) ? 0 : Date.now() - serverNow;
            // The Date header has whole seconds, so the link never shows longer than its 10 minutes.
            state.sheet.expiresAt = Math.min(Date.parse(link.expiresAt) + skew, Date.now() + LINK_MS);
            drawQr(link.qr);
            el("qr-card").classList.remove("dead");
            el("resume-url").value = link.url;
            el("copy-link").disabled = false;
            el("copy-link").textContent = "Copy";
            tickSheet();
            state.sheet.timer = setInterval(tickSheet, 1000);
        } catch (e) {
            if (!(e instanceof SignedOut)) {
                sheetFailed("Could not reach the server.");
            }
        } finally {
            state.sheet.working = false;
        }
    }

    function openSheet() {
        state.sheet.opener = document.activeElement;
        el("scrim").hidden = false;
        el("sheet").focus();
        makeLink();
    }

    function closeSheet() {
        clearInterval(state.sheet.timer);
        state.sheet.timer = null;
        el("scrim").hidden = true;
        el("resume-url").value = "";
        if (state.sheet.opener && state.sheet.opener.focus) {
            state.sheet.opener.focus();
        }
    }

    async function copyLink() {
        const input = el("resume-url");
        try {
            await navigator.clipboard.writeText(input.value);
        } catch (e) {
            input.select();
            return;
        }
        el("copy-link").textContent = "Copied";
        setTimeout(() => {
            el("copy-link").textContent = "Copy";
        }, COPIED_MS);
    }

    // ---------- test payment details ----------

    // A sandbox's PayPal sandbox card and buyer account, with sample billing details for the card form, so a judge
    // can pay a tip. Kept in the page only; the button stays hidden when neither the card nor the account is set.
    async function loadTestPayment() {
        const details = await getJson("/api/owner/test-payment");
        const card = details && details.card ? details.card : null;
        const paypal = details && details.paypal ? details.paypal : null;
        if (!card && !paypal) {
            return;
        }
        const billing = card && details.billing ? details.billing : null;
        fillPayGroup("pay-card", card && [
            ["Card number", card.number],
            ["Expiry", card.expiry],
            ["CVC", card.cvc],
        ]);
        fillPayGroup("pay-billing", billing && [
            ["Name", billing.name],
            ["Postcode", billing.postcode],
            ["Mobile", billing.mobile],
            ["Email", billing.email, "billing email"],
        ]);
        fillPayGroup("pay-paypal", paypal && [
            ["Email", paypal.email, "PayPal email"],
            ["Password", paypal.password, "PayPal password"],
        ]);
        el("open-pay").hidden = false;
    }

    function fillPayGroup(id, rows) {
        const list = el(id + "-rows");
        list.replaceChildren();
        el(id).hidden = !rows;
        if (!rows) {
            return;
        }
        // Each row is a label, its value and, where the label alone is ambiguous, the name a screen reader hears.
        for (const [label, raw, spoken] of rows) {
            const value = typeof raw === "string" ? raw : "";
            const name = spoken || label;
            const text = make("span", "pay-value", value);
            const copy = make("button", "ghost", "Copy");
            copy.type = "button";
            copy.setAttribute("aria-label", "Copy " + name);
            copy.addEventListener("click", () => copyValue(copy, value, text, name));
            const data = make("dd");
            data.append(text, copy);
            const row = make("div", "pay-row");
            row.append(make("dt", null, label), data);
            list.append(row);
        }
    }

    // Without clipboard access (an old browser, or permission refused) the value is selected for copying by hand.
    async function copyValue(copy, value, text, name) {
        try {
            await navigator.clipboard.writeText(value);
        } catch (e) {
            const range = document.createRange();
            range.selectNodeContents(text);
            const selection = window.getSelection();
            selection.removeAllRanges();
            selection.addRange(range);
            return;
        }
        copy.textContent = "Copied";
        copy.setAttribute("aria-label", "Copied " + name);
        setTimeout(() => {
            copy.textContent = "Copy";
            copy.setAttribute("aria-label", "Copy " + name);
        }, COPIED_MS);
    }

    function openPay() {
        state.pay.opener = document.activeElement;
        el("pay-scrim").hidden = false;
        el("pay-sheet").focus();
    }

    function closePay() {
        el("pay-scrim").hidden = true;
        if (state.pay.opener && state.pay.opener.focus) {
            state.pay.opener.focus();
        }
    }

    // The dialog that is open, if any: Continue on another device or Test payment details.
    function openDialog() {
        if (!el("scrim").hidden) {
            return { sheet: el("sheet"), close: closeSheet };
        }
        if (!el("pay-scrim").hidden) {
            return { sheet: el("pay-sheet"), close: closePay };
        }
        return null;
    }

    // Escape closes the open dialog and Tab stays inside it while it is open.
    function sheetKeys(event) {
        const dialog = openDialog();
        if (dialog === null) {
            return;
        }
        if (event.key === "Escape") {
            dialog.close();
            return;
        }
        if (event.key !== "Tab") {
            return;
        }
        const focusable = Array.from(dialog.sheet.querySelectorAll("button, input")).filter(
            (node) => !node.disabled && node.offsetParent !== null,
        );
        if (focusable.length === 0) {
            return;
        }
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.sheet)) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
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
            // The zone formats every time, so nothing is shown before it; the devices name today's currency.
            await loadMe();
            await refreshDevices();
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
        // The public sample is the same for everyone and never changes during the day: no socket, no polling.
        if (!state.readOnly && !state.leaving && state.socket === null && state.reconnectTimer === null) {
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
        el("open-sheet").addEventListener("click", openSheet);
        el("split-toggle").checked = EMBEDDED;
        el("split-toggle").addEventListener("change", () => {
            if (el("split-toggle").checked) {
                window.location.assign("/owner/live");
            } else {
                window.top.location.assign("/owner");
            }
        });
        if (EMBEDDED) {
            el("sign-out").target = "_top";
        }
        el("close-sheet").addEventListener("click", closeSheet);
        el("copy-link").addEventListener("click", copyLink);
        el("scrim").addEventListener("click", (event) => {
            if (event.target === el("scrim")) {
                closeSheet();
            }
        });
        el("open-pay").addEventListener("click", openPay);
        el("close-pay").addEventListener("click", closePay);
        el("pay-scrim").addEventListener("click", (event) => {
            if (event.target === el("pay-scrim")) {
                closePay();
            }
        });
        document.addEventListener("keydown", sheetKeys);
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
