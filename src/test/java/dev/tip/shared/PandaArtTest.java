package dev.tip.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every page draws the same panda. Each page keeps its own inline copy of the symbols it uses, and every copy
 * must match the canonical sprite {@code src/test/resources/art/panda-sprite.svg}, a byte identical copy of the
 * approved {@code briefs/assets/08-panda/panda-sprite.svg}, after whitespace is
 * normalised, so a page whose art drifts fails the build.
 */
class PandaArtTest {

    private static final Path SPRITE = Path.of("src/test/resources/art/panda-sprite.svg");

    private static final Path STATIC = Path.of("src/main/resources/static");

    private static final Pattern SYMBOL = Pattern.compile("<g id=\"([a-z-]+)\"");

    private static final Pattern USE = Pattern.compile("<use href=\"#([a-z-]+)\"");

    private static final Pattern PEEK = Pattern.compile("<svg class=\"peek\"[^>]*>(.*?)</svg>", Pattern.DOTALL);

    /** The pages that draw the panda and the symbols each must carry. */
    private static final Map<String, Set<String>> PAGES = Map.of(
            "tip.html", Set.of("head", "eyes-open", "paws"),
            "404.html", Set.of("head", "eyes-open", "paws"),
            "judges.html", Set.of("head", "eyes-open", "paws"),
            "resume.html", Set.of("head", "eyes-open", "paws"),
            "owner-login.html", Set.of("head", "eyes-open", "paws"),
            "owner.html", Set.of("head", "eyes-open", "eyes-shut", "paws-eyes", "paws"));

    @Test
    void theSpriteHasEverySymbol() throws IOException {
        assertThat(symbols(read(SPRITE)).keySet())
                .containsExactly("head", "eyes-open", "eyes-shut", "paws-eyes", "paws", "face");
    }

    @Test
    void everyPageCarriesTheSymbolsItUsesExactlyAsTheSprite() throws IOException {
        Map<String, String> sprite = symbols(read(SPRITE));
        try (Stream<Path> files = Files.list(STATIC)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String name = file.getFileName().toString();
                String html = read(file);
                Map<String, String> defined = symbols(html);
                defined.keySet().retainAll(sprite.keySet());

                Set<String> used = new TreeSet<>();
                Matcher use = USE.matcher(html);
                while (use.find()) {
                    if (sprite.containsKey(use.group(1))) {
                        used.add(use.group(1));
                    }
                }
                assertThat(defined.keySet())
                        .as("%s defines every panda symbol it uses", name)
                        .containsAll(used);
                assertThat(defined.keySet())
                        .as("%s carries its panda symbols", name)
                        .containsAll(PAGES.getOrDefault(name, Set.of()));
                defined.forEach((id, art) -> assertThat(art)
                        .as("%s symbol %s matches %s", name, id, SPRITE)
                        .isEqualTo(sprite.get(id)));
            }
        }
    }

    @Test
    void everyPeekingPandaUsesTheSharedHead() throws IOException {
        for (String name : PAGES.keySet()) {
            String html = read(STATIC.resolve(name));
            Matcher peek = PEEK.matcher(html);
            int count = 0;
            while (peek.find()) {
                count++;
                assertThat(peek.group(1)).as("a peek in %s", name).contains("<use href=\"#head\"/>");
            }
            assertThat(count).as("peeks in %s", name).isPositive();
        }
    }

    @Test
    void comparisonIgnoresOnlyWhitespace() {
        assertThat(symbols("<g id=\"paws\">\n  <ellipse cx=\"22\"/>\n</g>"))
                .isEqualTo(symbols("<g id=\"paws\"><ellipse   cx=\"22\"/></g>"))
                .isNotEqualTo(symbols("<g id=\"paws\"><ellipse cx=\"23\"/></g>"));
        assertThat(symbols("<g id=\"head\"><g><circle/></g></g><g id=\"paws\"></g>"))
                .containsEntry("head", "<g id=\"head\"><g><circle/></g></g>")
                .containsEntry("paws", "<g id=\"paws\"></g>");
    }

    /** Each {@code <g id>} element, up to its matching close tag, with whitespace normalised, in order. */
    private static Map<String, String> symbols(String markup) {
        Map<String, String> found = new LinkedHashMap<>();
        Matcher symbol = SYMBOL.matcher(markup);
        while (symbol.find()) {
            found.put(symbol.group(1), normalise(element(markup, symbol.start())));
        }
        return found;
    }

    private static String element(String markup, int start) {
        int depth = 0;
        int at = start;
        while (true) {
            int open = markup.indexOf("<g", at);
            int close = markup.indexOf("</g>", at);
            assertThat(close).as("closing tag of the group at %d", start).isNotNegative();
            if (open >= 0 && open < close && isGroupTag(markup, open)) {
                depth++;
                at = open + 2;
            } else if (open >= 0 && open < close) {
                at = open + 2;
            } else {
                depth--;
                at = close + 4;
                if (depth == 0) {
                    return markup.substring(start, at);
                }
            }
        }
    }

    private static boolean isGroupTag(String markup, int open) {
        char next = markup.charAt(open + 2);
        return next == ' ' || next == '>' || Character.isWhitespace(next);
    }

    private static String normalise(String markup) {
        return markup.replaceAll("\\s+", " ").replaceAll(">\\s+<", "><").trim();
    }

    private static String read(Path file) throws IOException {
        assertThat(file).isRegularFile();
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
