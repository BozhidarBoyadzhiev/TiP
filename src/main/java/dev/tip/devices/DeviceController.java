package dev.tip.devices;

import dev.tip.devices.api.DeviceDirectory;
import dev.tip.devices.api.DeviceSlug;
import dev.tip.devices.api.DeviceSummary;
import dev.tip.paypal.api.PayPalPublicConfig;
import dev.tip.shared.TipProperties;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/public/devices")
class DeviceController {

    private final DeviceDirectory devices;
    private final TipProperties tipProperties;
    private final PayPalPublicConfig payPal;

    DeviceController(DeviceDirectory devices, TipProperties tipProperties, PayPalPublicConfig payPal) {
        this.devices = devices;
        this.tipProperties = tipProperties;
        this.payPal = payPal;
    }

    @GetMapping("/{slug}")
    DevicePublicConfig get(@PathVariable @Pattern(regexp = DeviceSlug.PATTERN) String slug) {
        DeviceSummary device = devices.getBySlug(slug);
        return new DevicePublicConfig(
                device.displayName(),
                tipProperties.currency(),
                tipProperties.presetAmountsMinor(),
                tipProperties.minMinor(),
                tipProperties.maxMinor(),
                payPal.clientId());
    }

    record DevicePublicConfig(
            String displayName,
            String currency,
            List<Long> presetAmountsMinor,
            long minMinor,
            long maxMinor,
            String paypalClientId) {}
}
