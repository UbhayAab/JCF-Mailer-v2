package com.jarurat.mailer.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The composer offers one input per merge tag it finds, and the sender substitutes
 * on the same pattern. If these two ever disagree the failure is silent: the panel
 * prompts for a field the renderer will not fill, or omits one it will, and the
 * blast goes out with a gap nobody was asked about.
 */
class MergeTagsTest {

    @Test
    @DisplayName("tags are found across subject, preheader and body, in reading order")
    void extractsInOrder() {
        assertThat(MergeTags.extract(
                "{{FIRST_NAME}}, your slot is confirmed",
                "A note for {{CITY}}",
                "<p>Dear {{NAME}}, see you at {{VENUE}}</p>"))
                .containsExactly("FIRST_NAME", "CITY", "NAME", "VENUE");
    }

    @Test
    @DisplayName("the same tag written three ways is one field")
    void collapsesSpellings() {
        assertThat(MergeTags.extract("{{name}} {{NAME}} {{ Name }}"))
                .containsExactly("NAME");
    }

    @Test
    @DisplayName("the sender's own tags are never offered as fields")
    void reservedTagsAreHidden() {
        assertThat(MergeTags.extract("<a href=\"{{UNSUBSCRIBE_LINK}}\">Stop</a> {{NAME}}"))
                .containsExactly("NAME");
    }

    @Test
    @DisplayName("a tracked link is not a merge tag")
    void trackTagIsNotAField() {
        // {{TRACK:https://...}} contains a colon and slashes, which the pattern's
        // character class excludes, so it never looked like a field in the first place.
        assertThat(MergeTags.extract("<a href=\"{{TRACK:https://jarurat.care/register}}\">Go</a>"))
                .isEmpty();
    }

    @Test
    @DisplayName("nothing to find is not an error")
    void handlesEmptyInput() {
        assertThat(MergeTags.extract((String) null)).isEmpty();
        assertThat(MergeTags.extract("", null, "plain text with no tags")).isEmpty();
    }

    @Test
    @DisplayName("a tag inside an href is found like any other")
    void findsTagsInsideAttributes() {
        assertThat(MergeTags.extract("<a href=\"{{LANDING}}\">Register</a>"))
                .containsExactly("LANDING");
    }

    @Test
    @DisplayName("missingFrom names exactly the tags a caller left blank")
    void reportsMissingValues() {
        Map<String, String> supplied = new LinkedHashMap<>();
        supplied.put("name", "Dr. Akanksha");
        supplied.put("HOSPITAL", "   ");   // blank counts as missing, not as supplied

        assertThat(MergeTags.missingFrom(supplied, "Hi {{NAME}} at {{HOSPITAL}} in {{CITY}}"))
                .containsExactly("HOSPITAL", "CITY");
    }

    @Test
    @DisplayName("sample values look like real data for tags we recognise")
    void samplesAreRecognisable() {
        assertThat(MergeTags.sampleFor("FIRST_NAME", null)).isEqualTo("Dr. Akanksha");
        assertThat(MergeTags.sampleFor("EMAIL", "test@jarurat.care")).isEqualTo("test@jarurat.care");
        assertThat(MergeTags.sampleFor("HOSPITAL", null)).isEqualTo("Tata Memorial Hospital");
    }

    @Test
    @DisplayName("an unrecognised tag falls back to its own words, never to invented data")
    void unknownTagsAreObviouslyPlaceholders() {
        // Inventing a plausible value for a field with no data source would make a
        // broken template look like a working one.
        assertThat(MergeTags.sampleFor("REFERRED_BY", null)).isEqualTo("Referred By");
    }

    @Test
    @DisplayName("form parameters unpack into an upper-cased merge map")
    void unpacksFormParameters() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("id", "41");
        params.put("to", "test@jarurat.care");
        params.put("merge.first_name", "Dr. Akanksha");
        params.put("merge.HOSPITAL", "AIIMS Delhi");
        params.put("merge.", "ignored, no tag name");

        assertThat(MergeTags.fromParams(params, "merge."))
                .containsExactly(
                        Map.entry("FIRST_NAME", "Dr. Akanksha"),
                        Map.entry("HOSPITAL", "AIIMS Delhi"));
    }

    @Test
    @DisplayName("the shared pattern is the one the renderer substitutes on")
    void patternMatchesRendererExpectations() {
        assertThat(MergeTags.PATTERN.matcher("{{NAME}}").find()).isTrue();
        assertThat(MergeTags.PATTERN.matcher("{{ NAME }}").find()).isTrue();
        assertThat(MergeTags.PATTERN.matcher("{{FIRST_NAME2}}").find()).isTrue();
        assertThat(MergeTags.PATTERN.matcher("{{ NA ME }}").find())
                .as("a space inside the name is not a tag").isFalse();
        assertThat(MergeTags.PATTERN.matcher("{NAME}").find())
                .as("single braces are not a tag").isFalse();
    }
}
