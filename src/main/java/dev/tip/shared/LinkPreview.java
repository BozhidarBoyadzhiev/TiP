package dev.tip.shared;

import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Fills the absolute URLs of a page's link preview tags. Both come from {@code TIP_PUBLIC_BASE_URL},
 * never from the request's {@code Host} header, so a forged host cannot end up in a shared card.
 */
@Component
public class LinkPreview {

    static final String IMAGE_PATH = "/images/og-image-panda.png";

    private final String baseUrl;

    LinkPreview(TipProperties properties) {
        this.baseUrl = properties.publicBaseUrl().strip().replaceAll("/+$", "");
    }

    /**
     * Replaces {@code {{OG_URL}}} with the page's canonical URL and {@code {{OG_IMAGE}}} with the preview
     * image's URL. The path must be one the caller has already validated.
     */
    public String fill(String template, String pagePath) {
        return template.replace("{{OG_URL}}", HtmlUtils.htmlEscape(baseUrl + pagePath))
                .replace("{{OG_IMAGE}}", HtmlUtils.htmlEscape(baseUrl + IMAGE_PATH));
    }
}
