"use strict";

// The side by side view of a judge's sandbox. The page carries nothing about the owner: it asks
// /api/owner/devices for the sandbox's panda, then opens its tip page, its panda page and the dashboard
// in the three frames, each with ?embed, which only changes how they look. Desktop only: a window
// narrower than 1100px goes back to the plain dashboard, now or when it is resized. The two gaps between the
// columns are separators that resize them; nothing is remembered, so a reload starts from the default widths.
(function () {
    const WIDE = window.matchMedia("(min-width: 1100px)");
    const DEFAULT_TIP = 390;
    const DEFAULT_DASH = 460;
    const MIN_TIP = 340;
    const MIN_PANDA = 280;
    const MIN_DASH = 400;
    const KEY_STEP = 16;

    function el(id) {
        return document.getElementById(id);
    }

    function toDashboard() {
        window.location.replace("/owner");
    }

    async function problemCode(response) {
        try {
            const body = await response.json();
            return body && typeof body.code === "string" ? body.code : null;
        } catch (e) {
            return null;
        }
    }

    async function start() {
        let response;
        try {
            response = await fetch("/api/owner/devices", {
                credentials: "same-origin",
                cache: "no-store",
                headers: { Accept: "application/json" },
            });
        } catch (e) {
            el("load-error").hidden = false;
            return;
        }
        if (response.status === 401) {
            const expired = (await problemCode(response)) === "SANDBOX_EXPIRED";
            window.location.assign(expired ? "/judges?sandboxExpired" : "/judges");
            return;
        }
        if (!response.ok) {
            el("load-error").hidden = false;
            return;
        }
        const devices = await response.json();
        const device = Array.isArray(devices) ? devices[0] : null;
        if (!device || typeof device.slug !== "string") {
            toDashboard();
            return;
        }
        const slug = encodeURIComponent(device.slug);
        el("frame-tip").src = "/t/" + slug + "?embed";
        el("frame-panda").src = "/p/" + slug + "?embed";
        el("frame-dash").src = "/owner?embed";
    }

    // ---------- resizable columns ----------

    const widths = { tip: DEFAULT_TIP, dash: DEFAULT_DASH };

    // The width the three columns share: the stage without its padding and the two separators.
    function room() {
        const stage = el("stage");
        const style = getComputedStyle(stage);
        const handles = el("handle-tip").offsetWidth + el("handle-dash").offsetWidth;
        return stage.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight) - handles;
    }

    function clamp(value, min, max) {
        return Math.max(min, Math.min(max, value));
    }

    // The widest a column may be with the other side column as it is and the panda at its minimum.
    function maxTip() {
        return Math.max(MIN_TIP, room() - MIN_PANDA - widths.dash);
    }

    function maxDash() {
        return Math.max(MIN_DASH, room() - MIN_PANDA - widths.tip);
    }

    function isTip(handle) {
        return handle === el("handle-tip");
    }

    function widthOf(handle) {
        return isTip(handle) ? widths.tip : widths.dash;
    }

    function setWidth(handle, width) {
        if (isTip(handle)) {
            widths.tip = clamp(width, MIN_TIP, maxTip());
        } else {
            widths.dash = clamp(width, MIN_DASH, maxDash());
        }
        apply();
    }

    function apply() {
        const stage = el("stage");
        stage.style.setProperty("--tip", Math.round(widths.tip) + "px");
        stage.style.setProperty("--dash", Math.round(widths.dash) + "px");
        describe(el("handle-tip"), widths.tip, MIN_TIP, maxTip());
        describe(el("handle-dash"), widths.dash, MIN_DASH, maxDash());
    }

    function describe(handle, now, min, max) {
        handle.setAttribute("aria-valuenow", String(Math.round(now)));
        handle.setAttribute("aria-valuemin", String(min));
        handle.setAttribute("aria-valuemax", String(Math.round(max)));
    }

    // A narrower window takes room from the dashboard first, then from the tip page, down to their minimums.
    function fit() {
        widths.dash = clamp(widths.dash, MIN_DASH, maxDash());
        widths.tip = clamp(widths.tip, MIN_TIP, maxTip());
        apply();
    }

    // Moving a separator to the right widens the tip page or narrows the dashboard. While dragging, a
    // transparent cover lies over the frames, so they never take the pointer.
    function resizable(handle) {
        const direction = isTip(handle) ? 1 : -1;
        let start = null;
        const stop = () => {
            start = null;
            el("drag-cover").hidden = true;
            document.body.classList.remove("dragging");
        };
        handle.addEventListener("pointerdown", (event) => {
            if (event.button !== 0) {
                return;
            }
            event.preventDefault();
            handle.focus();
            handle.setPointerCapture(event.pointerId);
            start = { x: event.clientX, width: widthOf(handle) };
            el("drag-cover").hidden = false;
            document.body.classList.add("dragging");
        });
        handle.addEventListener("pointermove", (event) => {
            if (start !== null) {
                setWidth(handle, start.width + direction * (event.clientX - start.x));
            }
        });
        handle.addEventListener("pointerup", stop);
        handle.addEventListener("pointercancel", stop);
        handle.addEventListener("lostpointercapture", stop);
        handle.addEventListener("keydown", (event) => {
            if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
                event.preventDefault();
                const step = event.key === "ArrowRight" ? KEY_STEP : -KEY_STEP;
                setWidth(handle, widthOf(handle) + direction * step);
            }
        });
        // A double click puts the separator's side column back to its default width.
        handle.addEventListener("dblclick", () => setWidth(handle, isTip(handle) ? DEFAULT_TIP : DEFAULT_DASH));
    }

    if (!WIDE.matches) {
        toDashboard();
        return;
    }
    WIDE.addEventListener("change", () => {
        if (!WIDE.matches) {
            toDashboard();
        }
    });
    document.addEventListener("DOMContentLoaded", () => {
        resizable(el("handle-tip"));
        resizable(el("handle-dash"));
        fit();
        window.addEventListener("resize", fit);
        start();
    });
})();
