"use strict";

// The side by side view of a judge's sandbox. The page carries nothing about the owner: it asks
// /api/owner/devices for the sandbox's panda, then opens its tip page, its panda page and the dashboard
// in the three frames, each with ?embed, which only changes how they look. Desktop only: a window
// narrower than 1100px goes back to the plain dashboard, now or when it is resized.
(function () {
    const WIDE = window.matchMedia("(min-width: 1100px)");

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

    if (!WIDE.matches) {
        toDashboard();
        return;
    }
    WIDE.addEventListener("change", () => {
        if (!WIDE.matches) {
            toDashboard();
        }
    });
    document.addEventListener("DOMContentLoaded", start);
})();
