package com.jarurat.mailer.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A subject line is not HTML, and this is the test that says so.
 *
 * The body escaper is correct and necessary: merge values land inside href="..."
 * and an unescaped quote there is a script injection into every recipient's mail.
 * Running a Subject header through that same escaper is a different mistake, and
 * one that shipped - "Ram & Co" reached inboxes as "Ram &amp; Co". These two rules
 * have to be tested together or the next person to harden one will break the other.
 */
class SubjectRenderingTest {

    private final SesSender ses = new SesSender("ap-south-1", "from@example.com", "Test",
            "reply@example.com", 12, "", "https://mailer.example.com");

    // ------------------------------------------------------------------
    // Subjects: no HTML escaping
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an ampersand in a subject stays an ampersand")
    void ampersandSurvives() {
        assertThat(ses.renderSubject("An offer from {{COMPANY}}", Map.of("COMPANY", "Ram & Co")))
                .isEqualTo("An offer from Ram & Co");
    }

    @Test
    @DisplayName("an apostrophe in a name stays an apostrophe")
    void apostropheSurvives() {
        assertThat(ses.renderSubject("{{FIRST_NAME}}, your slot is confirmed",
                Map.of("FIRST_NAME", "Priya O'Brien")))
                .isEqualTo("Priya O'Brien, your slot is confirmed");
    }

    @Test
    @DisplayName("quotes and angle brackets survive too")
    void otherPunctuationSurvives() {
        assertThat(ses.renderSubject("{{NAME}} <- confirmed",
                Map.of("NAME", "Dr. \"Sunny\" Mehta")))
                .isEqualTo("Dr. \"Sunny\" Mehta <- confirmed");
    }

    @Test
    @DisplayName("a newline in a merge value cannot split the header")
    void headerInjectionIsNeutralised() {
        String subject = ses.renderSubject("Hello {{NAME}}",
                Map.of("NAME", "Priya\r\nBcc: victim@example.com"));

        assertThat(subject).doesNotContain("\r").doesNotContain("\n");
        assertThat(subject).isEqualTo("Hello Priya Bcc: victim@example.com");
    }

    @Test
    @DisplayName("a runaway merge value cannot produce an unbounded header")
    void subjectIsCapped() {
        String huge = "x".repeat(5000);
        assertThat(ses.renderSubject("{{THING}}", Map.of("THING", huge)).length())
                .isLessThanOrEqualTo(500);
    }

    @Test
    @DisplayName("an unsupplied tag blanks rather than shipping braces to a recipient")
    void unresolvedTagsBlank() {
        assertThat(ses.renderSubject("Hi {{FIRST_NAME}}, about {{ROLE}}",
                Map.of("FIRST_NAME", "Priya")))
                .isEqualTo("Hi Priya, about");
    }

    @Test
    @DisplayName("a null subject is empty, not the string null")
    void nullSubjectIsSafe() {
        assertThat(ses.renderSubject(null, Map.of())).isEmpty();
    }

    // ------------------------------------------------------------------
    // Bodies: escaping still on
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a merge value still cannot break out of an href in the body")
    void bodyStillEscapesAttributeBreakout() {
        String html = ses.renderTransactional("<a href=\"{{LANDING}}\">Go</a>",
                Map.of("LANDING", "https://ok.example\" onmouseover=\"alert(1)"));

        assertThat(html)
                .as("the quote must not close the attribute")
                .doesNotContain("\" onmouseover=\"")
                .contains("&quot;");
    }

    @Test
    @DisplayName("a merge value still cannot open a tag in the body")
    void bodyStillEscapesMarkup() {
        assertThat(ses.renderTransactional("<p>{{NAME}}</p>", Map.of("NAME", "<script>alert(1)</script>")))
                .doesNotContain("<script>")
                .contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("the two rules coexist: same value, escaped in the body, plain in the subject")
    void bodyAndSubjectDisagreeOnPurpose() {
        Map<String, String> merge = Map.of("COMPANY", "Ram & Co");

        assertThat(ses.renderSubject("From {{COMPANY}}", merge)).isEqualTo("From Ram & Co");
        assertThat(ses.renderTransactional("<p>From {{COMPANY}}</p>", merge))
                .isEqualTo("<p>From Ram &amp; Co</p>");
    }
}
