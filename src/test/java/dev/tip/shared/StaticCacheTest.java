package dev.tip.shared;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tip.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class StaticCacheTest extends IntegrationTest {

    private static final String[] STYLES_AND_SCRIPTS = {
        "/css/brand.css", "/css/tip.css", "/css/panda.css", "/fonts/fonts.css", "/js/brand.js", "/js/tip.js"
    };

    @Autowired
    MockMvc mvc;

    @Test
    void stylesAndScriptsRevalidateWithLastModified() throws Exception {
        for (String path : STYLES_AND_SCRIPTS) {
            mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-cache"))
                    .andExpect(header().exists("Last-Modified"))
                    .andExpect(header().doesNotExist("Pragma"))
                    .andExpect(header().doesNotExist("Expires"));
        }
    }

    @Test
    void repeatVisitToAnUnchangedStyleOrScriptIsNotModified() throws Exception {
        for (String path : STYLES_AND_SCRIPTS) {
            String lastModified =
                    mvc.perform(get(path)).andReturn().getResponse().getHeader("Last-Modified");

            mvc.perform(get(path).header("If-Modified-Since", lastModified))
                    .andExpect(status().isNotModified())
                    .andExpect(content().string(""))
                    .andExpect(header().string("Cache-Control", "no-cache"));
        }
    }

    @Test
    void htmlPagesAreNeverStoredBecauseOfTheirNonce() throws Exception {
        for (String path : new String[] {"/t/demo-panda", "/p/demo-panda", "/t/no-such-panda"}) {
            mvc.perform(get(path))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andExpect(header().string("Cache-Control", containsString("no-store")));
        }
        mvc.perform(get("/nope").accept(MediaType.TEXT_HTML))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test
    void fontFilesAreCachedForThirtyDays() throws Exception {
        mvc.perform(get("/fonts/fredoka/fredoka-latin-700-normal.woff2"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("font/woff2"))
                .andExpect(header().string("Cache-Control", "max-age=2592000, public"))
                .andExpect(header().exists("Last-Modified"));
    }
}
