package dev.tip.tipping;

import dev.tip.devices.api.DeviceSlug;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TipController {

    private final TipService tips;
    private final TipRateLimiter rateLimiter;

    TipController(TipService tips, TipRateLimiter rateLimiter) {
        this.tips = tips;
        this.rateLimiter = rateLimiter;
    }

    /** The client IP is the one Tomcat resolved from the trusted proxy's headers, see server.forward-headers-strategy. */
    @PostMapping("/api/public/devices/{slug}/tips")
    @ResponseStatus(HttpStatus.CREATED)
    CreateTipResponse create(
            @PathVariable @Pattern(regexp = DeviceSlug.PATTERN) String slug,
            @Valid @RequestBody CreateTipRequest request,
            HttpServletRequest http) {
        rateLimiter.checkOrder(http.getRemoteAddr());
        TipService.TipCreated created = tips.create(slug, request.amountMinor(), request.currency(), request.message());
        return new CreateTipResponse(created.tipId(), created.paypalOrderId());
    }

    @PostMapping("/api/public/tips/{tipId}/capture")
    CaptureTipResponse capture(@PathVariable UUID tipId, HttpServletRequest http) {
        rateLimiter.checkCapture(http.getRemoteAddr());
        return new CaptureTipResponse(tips.capture(tipId));
    }

    record CreateTipRequest(
            @NotNull @Positive Long amountMinor,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Size(max = 280) String message) {}

    record CreateTipResponse(UUID tipId, String paypalOrderId) {}

    record CaptureTipResponse(TipStatus status) {}
}
