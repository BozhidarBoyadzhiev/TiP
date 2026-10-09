"use strict";

// The virtual panda: watches /ws/observe/{slug} and plays the LED patterns of docs/DEVICE_PROTOCOL.md
// with the same timings as the physical panda.
(function () {
    const QUEUE_SIZE = 4;
    const BACKOFF_SECONDS = [1, 2, 4, 8, 16, 30];
    const JITTER = 0.2;
    const FLICK_PERIOD_MS = 1000;
    const FLICK_MS = 30;

    // Each step is [duration ms, level at start, level at end], levels 0 (off) to 1 (full, duty 255).
    function repeat(times, steps) {
        let all = [];
        for (let i = 0; i < times; i++) {
            all = all.concat(steps);
        }
        return all;
    }

    const STROBE = repeat(12, [
        [60, 1, 1],
        [60, 0, 0],
    ]);

    const REACTIONS = {
        POSITIVE: {
            caption: "Kind message",
            colour: "#ff6b8b",
            steps: repeat(3, [
                [400, 0, 1],
                [600, 1, 0],
                [200, 0, 0],
            ]),
        },
        NEGATIVE: {
            caption: "Rude message",
            colour: "#ff3b2f",
            steps: STROBE.concat([[250, 0, 0]], STROBE),
        },
        NEUTRAL: {
            caption: "Tip received",
            colour: "#ffb347",
            steps: [[700, 1, 1]],
        },
    };

    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

    const state = {
        slug: null,
        connected: false,
        stopped: false,
        attempt: 0,
        queue: [],
        playing: null,
        frame: null,
    };

    function el(id) {
        return document.getElementById(id);
    }

    function slugFromPath() {
        const parts = window.location.pathname.split("/").filter(Boolean);
        return parts.length >= 2 && parts[0] === "p" ? decodeURIComponent(parts[1]) : null;
    }

    function duration(steps) {
        return steps.reduce((total, step) => total + step[0], 0);
    }

    function levelAt(steps, elapsed) {
        let start = 0;
        for (const [length, from, to] of steps) {
            if (elapsed < start + length) {
                return from + (to - from) * ((elapsed - start) / length);
            }
            start += length;
        }
        return 0;
    }

    function setLed(level) {
        el("led-core").setAttribute("opacity", String(level));
        el("led-glow").setAttribute("opacity", String(level));
    }

    function setColour(colour) {
        el("panda").style.setProperty("--led-colour", colour);
    }

    function showCaption(text) {
        const caption = el("caption");
        caption.classList.remove("shown");
        // Reading layout restarts the caption's entry animation.
        void caption.offsetWidth;
        caption.textContent = text;
        caption.classList.add("shown");
    }

    function enqueue(reaction) {
        state.queue.push(reaction);
        if (state.queue.length > QUEUE_SIZE) {
            state.queue.shift();
        }
        if (!state.playing) {
            startNext(performance.now());
        }
        ensureLoop();
    }

    function startNext(now) {
        const name = state.queue.shift();
        const reaction = REACTIONS[name];
        state.playing = { reaction: reaction, start: now, length: duration(reaction.steps) };
        setColour(reaction.colour);
        showCaption(reaction.caption);
    }

    function ensureLoop() {
        if (state.frame === null) {
            state.frame = window.requestAnimationFrame(tick);
        }
    }

    // Plays the current reaction; with reduced motion it holds the light on for the pattern's length
    // instead of fading or strobing. With nothing playing and no connection, it gives the status flick.
    function tick(now) {
        state.frame = null;
        if (state.playing && now - state.playing.start >= state.playing.length) {
            state.playing = null;
            if (state.queue.length > 0) {
                startNext(now);
            }
        }
        if (state.playing) {
            const elapsed = now - state.playing.start;
            setLed(reducedMotion.matches ? 1 : levelAt(state.playing.reaction.steps, elapsed));
            ensureLoop();
            return;
        }
        if (!state.connected && !state.stopped && !reducedMotion.matches) {
            setColour(REACTIONS.NEUTRAL.colour);
            setLed(now % FLICK_PERIOD_MS < FLICK_MS ? 1 : 0);
            ensureLoop();
            return;
        }
        setLed(0);
    }

    function setStatus(name, text) {
        el("status").dataset.state = name;
        el("status-text").textContent = text;
    }

    function onMessage(event) {
        let message;
        try {
            message = JSON.parse(event.data);
        } catch (e) {
            return;
        }
        if (message && message.type === "reaction" && Object.hasOwn(REACTIONS, message.reaction)) {
            enqueue(message.reaction);
        }
    }

    function connect() {
        const scheme = window.location.protocol === "https:" ? "wss:" : "ws:";
        const socket = new WebSocket(
            scheme + "//" + window.location.host + "/ws/observe/" + encodeURIComponent(state.slug),
        );
        socket.addEventListener("open", () => {
            state.connected = true;
            state.attempt = 0;
            setStatus("live", "Live");
            ensureLoop();
        });
        socket.addEventListener("message", onMessage);
        socket.addEventListener("close", () => {
            state.connected = false;
            setStatus("reconnecting", "Reconnecting");
            ensureLoop();
            window.setTimeout(connect, nextDelayMillis());
        });
    }

    // 1, 2, 4, 8, 16, then 30 seconds, each with up to 20 percent random jitter.
    function nextDelayMillis() {
        const seconds = BACKOFF_SECONDS[Math.min(state.attempt, BACKOFF_SECONDS.length - 1)];
        state.attempt += 1;
        return seconds * 1000 * (1 + Math.random() * JITTER);
    }

    function fail(text) {
        state.stopped = true;
        el("notice").textContent = text;
        el("notice").hidden = false;
        setStatus("offline", "Offline");
    }

    async function start() {
        state.slug = slugFromPath();
        if (!state.slug) {
            fail("This panda does not exist.");
            return;
        }
        ensureLoop();
        let response;
        try {
            response = await fetch("/api/public/devices/" + encodeURIComponent(state.slug));
        } catch (e) {
            response = null;
        }
        if (response && response.status === 404) {
            fail("This panda does not exist.");
            return;
        }
        if (response && response.ok) {
            const device = await response.json();
            el("title").textContent = device.displayName;
            document.title = device.displayName;
        }
        connect();
    }

    start();
})();
