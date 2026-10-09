package dev.tip.tipping;

import dev.tip.shared.CspNonce;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the static tip page for the URL on the NFC tag, with this request's CSP nonce on its script
 * tag. The page reads the slug from its own path.
 */
@RestController
class TipPageController {

    private static final String NONCE_PLACEHOLDER = "{{CSP_NONCE}}";

    private final String template;

    TipPageController() {
        try {
            template = new ClassPathResource("static/tip.html").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Tip page template missing", e);
        }
    }

    @GetMapping("/t/{slug}")
    ResponseEntity<String> tipPage(HttpServletRequest request) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .body(template.replace(NONCE_PLACEHOLDER, CspNonce.of(request)));
    }
}
