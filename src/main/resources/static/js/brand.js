"use strict";

// The TiP brand every page shares, and the rule for when a device name only repeats the brand.
window.TipBrand = (function () {
    // "TiP" or "Tip integrated Panda" in any case or spacing is the brand itself, so pages do not repeat it.
    const BRAND_NAMES = ["tip", "tipintegratedpanda"];

    function isBrandName(name) {
        return typeof name !== "string" || BRAND_NAMES.includes(name.replace(/\s+/g, "").toLowerCase());
    }

    // Splits each hidden part of the brand into letters for the staggered reveal; a tap toggles it on touch screens.
    function setUp() {
        const brand = document.getElementById("brand");
        if (!brand) {
            return;
        }
        const parts = brand.querySelectorAll("[data-text]");
        parts.forEach((part) => {
            Array.from(part.dataset.text).forEach((character, position) => {
                const letter = document.createElement("span");
                letter.className = "l";
                letter.textContent = character;
                letter.style.setProperty("--d", String(position));
                part.appendChild(letter);
            });
        });
        // The open width of each hidden part is its letters' width in the loaded font at the current size.
        const measure = () => {
            parts.forEach((part) => part.parentElement.style.setProperty("--w", part.scrollWidth + "px"));
        };
        document.fonts.ready.then(measure);
        window.addEventListener("resize", measure);
        brand.addEventListener("click", () => {
            brand.setAttribute("aria-expanded", brand.getAttribute("aria-expanded") === "true" ? "false" : "true");
        });
    }

    setUp();

    return { isBrandName };
})();
