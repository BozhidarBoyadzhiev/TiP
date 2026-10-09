package dev.tip.tipping;

import dev.tip.paypal.api.PayPalGateway;
import dev.tip.paypal.api.WebhookTransmission;
import dev.tip.paypal.api.WebhookVerification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
class PayPalWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PayPalWebhookController.class);

    private final PayPalGateway payPal;
    private final PayPalWebhookProcessor processor;
    private final JsonMapper jsonMapper;

    PayPalWebhookController(PayPalGateway payPal, PayPalWebhookProcessor processor, JsonMapper jsonMapper) {
        this.payPal = payPal;
        this.processor = processor;
        this.jsonMapper = jsonMapper;
    }

    /** The body is read as a raw string and verified as is. It is parsed only after PayPal confirms it. */
    @PostMapping("/webhooks/paypal")
    ResponseEntity<Void> receive(
            @RequestBody String rawBody,
            @RequestHeader(name = "PAYPAL-AUTH-ALGO", required = false) String authAlgo,
            @RequestHeader(name = "PAYPAL-CERT-URL", required = false) String certUrl,
            @RequestHeader(name = "PAYPAL-TRANSMISSION-ID", required = false) String transmissionId,
            @RequestHeader(name = "PAYPAL-TRANSMISSION-SIG", required = false) String transmissionSig,
            @RequestHeader(name = "PAYPAL-TRANSMISSION-TIME", required = false) String transmissionTime) {
        WebhookTransmission transmission =
                new WebhookTransmission(authAlgo, certUrl, transmissionId, transmissionSig, transmissionTime);
        WebhookVerification verification = payPal.verifyWebhook(transmission, rawBody);
        switch (verification) {
            case VERIFIED -> {}
            case REJECTED -> {
                log.warn("Rejected PayPal webhook with transmission id {}", transmissionId);
                throw new TipExceptions.WebhookNotVerified();
            }
            case UNAVAILABLE -> throw new TipExceptions.WebhookVerificationUnavailable();
        }
        PayPalWebhookEvent event =
                PayPalWebhookEvent.parse(jsonMapper, rawBody).orElseThrow(TipExceptions.WebhookMalformed::new);
        processor.process(event);
        return ResponseEntity.ok().build();
    }
}
