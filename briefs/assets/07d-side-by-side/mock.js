/* PREVIEW ONLY. Stands in for the server so the real pages run without a backend:
   fetch, the WebSockets and the PayPal SDK are faked, and the frames talk through the wrapper page.
   None of this ships. */
(function () {
    "use strict";
    const PAGE = document.currentScript.dataset.page;
    const SLUG = "judge-7f3a";
    // Frames talk through the wrapper page (index.html), which relays to every frame.
    const bc = {
        postMessage(data) { window.parent.postMessage({ tipPreview: data }, "*"); },
        addEventListener(_type, fn) {
            window.addEventListener("message", (e) => { if (e.data && e.data.tipPreview) fn({ data: e.data.tipPreview }); });
        },
    };
    window.__PREVIEW_SLUG = SLUG;
    const split = new URLSearchParams(window.location.search).has("embed");
    if (split) {
        document.documentElement.classList.add("embedded");
    }

    const uuid = () =>
        "xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx".replace(/x/g, () => ((Math.random() * 16) | 0).toString(16));
    const json = (body, status) =>
        new Response(body === undefined ? "null" : JSON.stringify(body), {
            status: status || 200,
            headers: { "Content-Type": "application/json", Date: new Date().toUTCString() },
        });
    const delay = (ms) => new Promise((r) => setTimeout(r, ms));

    const DEVICE = {
        displayName: "Your panda",
        currency: "GBP",
        presetAmountsMinor: [200, 500, 1000, 2000],
        minMinor: 100,
        maxMinor: 10000,
        paypalClientId: "sb",
    };

    // ---------- a fake WebSocket the preview can push messages into ----------
    const sockets = [];
    class FakeSocket extends EventTarget {
        constructor(url) {
            super();
            this.url = url;
            this.readyState = 0;
            sockets.push(this);
            setTimeout(() => {
                this.readyState = 1;
                this.dispatchEvent(new Event("open"));
                if (this.onopen) this.onopen(new Event("open"));
            }, 250);
        }
        push(data) {
            const ev = new MessageEvent("message", { data: JSON.stringify(data) });
            this.dispatchEvent(ev);
            if (this.onmessage) this.onmessage(ev);
        }
        send() {}
        close() {}
    }
    FakeSocket.CONNECTING = 0;
    FakeSocket.OPEN = 1;
    FakeSocket.CLOSING = 2;
    FakeSocket.CLOSED = 3;
    window.WebSocket = FakeSocket;
    const pushAll = (data) => sockets.forEach((s) => s.push(data));

    // ---------- tip page ----------
    if (PAGE === "tip") {
        const original = window.location.href;
        let pending = null;
        window.fetch = async (url, init) => {
            const path = String(url);
            await delay(120);
            if (path.endsWith("/tips") && init && init.method === "POST") {
                const body = JSON.parse(init.body);
                pending = { id: uuid(), amountMinor: body.amountMinor, currency: body.currency, message: body.message };
                return json({ tipId: pending.id, paypalOrderId: "PREVIEW" }, 201);
            }
            if (path.endsWith("/capture")) {
                await delay(500);
                bc.postMessage({ type: "captured", tip: pending });
                return json({ status: "CAPTURED" });
            }
            if (path.startsWith("/api/public/devices/")) {
                return json(DEVICE);
            }
            return json({ code: "NOT_FOUND" }, 404);
        };

        // PayPal's SDK, faked: the same callbacks tip.js gives the real one.
        const fakePaypal = {
            Buttons(opts) {
                return {
                    render(selector) {
                        const box = document.querySelector(selector);
                        const make = (label, cls) => {
                            const b = document.createElement("button");
                            b.type = "button";
                            b.className = "pp-fake " + cls;
                            b.textContent = label;
                            return b;
                        };
                        const pp = make("PayPal", "pp-gold");
                        const card = make("Debit or Credit Card", "pp-dark");
                        box.replaceChildren(pp, card);
                        const actions = {
                            enable() { pp.disabled = card.disabled = false; },
                            disable() { pp.disabled = card.disabled = true; },
                        };
                        if (opts.onInit) opts.onInit({}, actions);
                        const go = async () => {
                            let ok = true;
                            await Promise.resolve(
                                opts.onClick({}, { resolve: () => Promise.resolve(), reject: () => { ok = false; return Promise.resolve(); } }),
                            );
                            if (!ok) return;
                            let order;
                            try { order = await opts.createOrder(); } catch (e) { return; }
                            const veil = document.createElement("div");
                            veil.className = "pp-veil";
                            veil.textContent = "PayPal sandbox (simulated in this preview)…";
                            document.body.append(veil);
                            await delay(900);
                            veil.remove();
                            await opts.onApprove({ orderID: order }, { restart() {} });
                        };
                        pp.addEventListener("click", go);
                        card.addEventListener("click", go);
                    },
                };
            },
        };
        const head = document.head;
        const append = head.appendChild.bind(head);
        head.appendChild = (node) => {
            if (node.tagName === "SCRIPT" && /paypal\.com/.test(node.src)) {
                window.paypal = fakePaypal;
                setTimeout(() => node.onload && node.onload(), 60);
                return node;
            }
            return append(node);
        };

        // Proposed: in side by side, "Watch the panda react" becomes "Tip again" (the panda is right there).
        document.addEventListener("DOMContentLoaded", () => {
            const again = document.getElementById("tip-again");
            if (again) again.addEventListener("click", () => window.location.replace(original));
        });
    }

    // ---------- panda page ----------
    if (PAGE === "panda") {
        window.fetch = async () => json(DEVICE);
        bc.addEventListener("message", (e) => {
            if (e.data.type === "reaction") {
                pushAll({ type: "reaction", reaction: e.data.reaction, repeats: e.data.repeats, at: new Date().toISOString() });
            }
        });
    }

    // ---------- dashboard ----------
    if (PAGE === "owner") {
        const now = Date.now();
        const ago = (min) => new Date(now - min * 60000).toISOString();
        const tips = [
            { min: 42, amountMinor: 500, message: "Thank you for the lovely set, you made our evening!", kind: "WARM", repeats: 2 },
            { min: 95, amountMinor: 1000, message: "Could you play Fly Me to the Moon next?", kind: "REQUEST", repeats: 3, ownerNote: "Wants Fly Me to the Moon" },
            { min: 130, amountMinor: 200, message: "haha the panda winked at me 🐼", kind: "PLAYFUL", repeats: 1 },
            { min: 180, amountMinor: 300, message: "", kind: null, repeats: 1 },
        ].map((t) => item(t, true));

        function item(t, demo) {
            return {
                id: t.id || uuid(),
                amountMinor: t.amountMinor,
                currency: "GBP",
                createdAt: t.createdAt || ago(t.min),
                status: "CAPTURED",
                refundStatus: null,
                message: t.flagged ? null : t.message || null,
                messageKind: t.flagged ? null : t.kind,
                reaction: t.flagged ? "PLAIN" : t.kind || "PLAIN",
                repeats: t.repeats,
                flagged: !!t.flagged,
                flagReason: t.flagged ? "Insulting" : null,
                unhidden: false,
                aiStatus: "READ",
                demo: demo,
                ownerNote: t.ownerNote || null,
                language: "en",
                languageName: "English",
                songTitle: t.kind === "REQUEST" ? "Fly Me to the Moon" : null,
            };
        }

        const me = {
            displayName: "Judge",
            timeZone: "Europe/London",
            aiResting: false,
            kind: "SANDBOX",
            readOnly: false,
            expiresAt: new Date(now + 70 * 3600000).toISOString(),
        };
        const today = () => ({
            date: new Date().toISOString().slice(0, 10),
            timeZone: "Europe/London",
            totals: [{ currency: "GBP", amountMinor: tips.filter((t) => t.status === "CAPTURED").reduce((a, t) => a + t.amountMinor, 0) }],
            tipCount: tips.length,
            requestCount: tips.filter((t) => t.messageKind === "REQUEST").length,
            hours: null,
            busiestHour: null,
        });

        window.fetch = async (url) => {
            const u = new URL(String(url), "https://x");
            const p = u.pathname;
            await delay(80);
            if (p === "/api/owner/me") return json(me);
            if (p === "/api/owner/devices") return json([{ slug: SLUG, name: "Your panda", online: true, lastSeenAt: null }]);
            if (p === "/api/owner/today") return json(today());
            if (p === "/api/owner/digest")
                return json({
                    bullets: ["People loved tonight's set.", "One song request: Fly Me to the Moon."],
                    messageCount: 3,
                    generatedAt: ago(30),
                });
            if (p === "/api/owner/requests")
                return json({
                    from: ago(7 * 1440).slice(0, 10),
                    to: new Date().toISOString().slice(0, 10),
                    topSong: { title: "Fly Me to the Moon", count: 1, latestAt: ago(95) },
                    grouped: "ai",
                    groups: [{ label: "Fly Me to the Moon", count: 1, latestAt: ago(95), tipIds: [tips[1].id] }],
                });
            if (p === "/api/owner/tips") {
                let list = tips.slice();
                if (u.searchParams.get("flagged") === "true") list = list.filter((t) => t.flagged);
                const kind = u.searchParams.get("kind");
                if (kind) list = list.filter((t) => t.messageKind === kind);
                const size = Number(u.searchParams.get("size") || 20);
                return json({ items: list.slice(0, size), page: 0, size: size, totalItems: list.length });
            }
            const one = p.match(/^\/api\/owner\/tips\/([^/]+)$/);
            if (one) {
                const t = tips.find((x) => x.id === one[1]);
                return t ? json(t) : json({ code: "TIP_NOT_FOUND" }, 404);
            }
            if (p.startsWith("/api/public/devices/")) return json(DEVICE);
            return json({ code: "NOT_FOUND" }, 404);
        };

        // What the server does after a capture: the AI reads the message, the panda plays, the dashboard updates.
        function classify(text) {
            const s = (text || "").toLowerCase();
            if (!s.trim()) return { kind: null };
            if (/\b(idiot|stupid|hate you|trash|sucks)\b/.test(s)) return { flagged: true };
            if (/\b(play|song|request|could you|can you)\b/.test(s)) return { kind: "REQUEST", ownerNote: "Song request: " + text.slice(0, 60) };
            if (/(haha|lol|😂|🐼|funny|wink)/.test(s)) return { kind: "PLAYFUL" };
            if (/\b(thank|love|lovely|amazing|beautiful|great|made my)\b/.test(s)) return { kind: "WARM" };
            return { kind: null };
        }
        const repeatsFor = (minor) => (minor >= 2000 ? 4 : minor >= 1000 ? 3 : minor >= 500 ? 2 : 1);

        bc.addEventListener("message", async (e) => {
            if (e.data.type !== "captured") return;
            const t = e.data.tip;
            await delay(1300);
            const c = classify(t.message);
            const repeats = c.flagged ? 1 : repeatsFor(t.amountMinor);
            const view = item(
                { id: t.id, amountMinor: t.amountMinor, message: t.message, kind: c.kind, flagged: c.flagged, ownerNote: c.ownerNote, repeats: repeats, createdAt: new Date().toISOString() },
                false,
            );
            tips.unshift(view);
            bc.postMessage({ type: "reaction", reaction: view.reaction, repeats: repeats });
            pushAll({ type: "tip", id: view.id });
            pushAll({ type: "today" });
        });

        // Proposed switch in the sandbox strip.
        document.addEventListener("DOMContentLoaded", () => {
            const toggle = document.getElementById("split-toggle");
            if (!toggle) return;
            toggle.checked = split;
            toggle.addEventListener("change", () => window.parent.postMessage({ type: "split", on: toggle.checked }, "*"));
        });
    }
})();
