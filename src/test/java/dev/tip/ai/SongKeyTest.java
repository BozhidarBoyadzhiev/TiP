package dev.tip.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SongKeyTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Wonderwall",
                "wonderwall",
                "WONDERWALL",
                "Wonderwall!",
                "\"Wonderwall\"",
                "  Wonderwall  ",
                "The Wonderwall",
                "the wonderwall?",
                "Wönderwall",
                "Ｗｏｎｄｅｒｗａｌｌ"
            })
    void spellingsOfOneSongShareAKey(String spelling) {
        assertThat(SongKey.of(spelling)).isEqualTo("wonderwall");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "La Vie en Rose|la vie en rose",
                "La  vie   en rose|la vie en rose",
                "Café del Mar|cafe del mar",
                "Don't Stop Me Now|dont stop me now",
                "Señorita|senorita",
                "AC/DC|acdc",
                "The|the",
                "Theme from Jaws|theme from jaws"
            })
    void normalisesCaseAccentsPunctuationWhitespaceAndALeadingThe(String song, String key) {
        assertThat(SongKey.of(song)).isEqualTo(key);
    }

    @Test
    void differentSongsKeepDifferentKeys() {
        assertThat(SongKey.of("Wonder Wall")).isNotEqualTo(SongKey.of("Wonderwall"));
        assertThat(SongKey.of("Wonderwall Live")).isNotEqualTo(SongKey.of("Wonderwall"));
        assertThat(SongKey.of("Them")).isNotEqualTo(SongKey.of("The M"));
        assertThat(SongKey.of("Hello")).isNotEqualTo(SongKey.of("Hallo"));
        assertThat(SongKey.of("A Wonderwall")).isNotEqualTo(SongKey.of("Wonderwall"));
    }

    @Test
    void nothingLeftIsNoKey() {
        assertThat(SongKey.of(null)).isNull();
        assertThat(SongKey.of("!!! ...")).isNull();
        assertThat(SongKey.of("   ")).isNull();
    }

    @Test
    void aKeyThatWouldOutgrowItsColumnIsNoKey() {
        // U+FDFA folds to 18 letters under NFKD.
        assertThat(SongKey.of("ﷺ".repeat(5))).isNull();
        assertThat(SongKey.of("a".repeat(80))).hasSize(80);
    }
}
