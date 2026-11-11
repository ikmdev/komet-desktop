/*
 * Copyright © 2015 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ikm.komet.desktop.alerts;

import dev.ikm.komet.desktop.maintenance.markdown.MarkdownTextModel;
import dev.ikm.tinkar.common.alert.AlertLog;
import dev.ikm.tinkar.common.alert.AlertObject;
import dev.ikm.tinkar.common.alert.AlertType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Warnings and Errors list's words: newest first, an alert's own characters kept, and the landing page's count. */
class WarningsAndErrorsTest {

    private static final Instant NOON = Instant.parse("2026-11-10T12:00:00Z");

    private static AlertLog.Entry entry(Instant when, AlertType type, String title, String description, boolean earlier) {
        return new AlertLog.Entry(when, when, 1, type, title, description, "", earlier);
    }

    private static String drawn(String markdown) {
        MarkdownTextModel model = MarkdownTextModel.of(markdown);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < model.size(); i++) {
            text.append(model.getPlainText(i)).append('\n');
        }
        return text.toString();
    }

    @Test
    void anEmptyListSaysWhatWillAppearInIt() {
        String markdown = WarningsAndErrors.markdown(List.of(), ZoneOffset.UTC);
        assertTrue(markdown.startsWith("No warnings or errors."));
        assertEquals("None", WarningsAndErrors.summary(List.of(), 0));
    }

    @Test
    void theNewestIsFirstAndEarlierRunsComeAfterThisOne() {
        List<AlertLog.Entry> entries = List.of(
                entry(NOON.minusSeconds(86_400), AlertType.WARNING, "From yesterday", "An import.", true),
                entry(NOON, AlertType.WARNING, "First today", "One.", false),
                entry(NOON.plusSeconds(60), AlertType.ERROR, "Second today", "Two.", false));

        String text = WarningsAndErrors.plainText(entries, ZoneOffset.UTC);

        int since = text.indexOf("Since Komet started");
        int second = text.indexOf("Error: Second today");
        int first = text.indexOf("Warning: First today");
        int before = text.indexOf("Before Komet started");
        int yesterday = text.indexOf("Warning: From yesterday");
        assertTrue(since >= 0 && since < second && second < first && first < before && before < yesterday, text);
        assertTrue(text.contains("2026-11-10 12:01:00"));
    }

    @Test
    void anAlertsOwnCharactersAreDrawnAsTheyAre() {
        String description = "Component 12884907155 [b1d4383c-3e51-54b8-9987-5cb677af3ab4] gained UUIDs"
                + " [045ce355-7f3a-5fce-8178-2e6c85c44d7c]: a public id <sharing> one_of_its *UUIDs* carried them.\n"
                + "- a second line\n1. and a third";
        List<AlertLog.Entry> entries = List.of(
                entry(NOON, AlertType.WARNING, "UUIDs added to a component", description, false));

        String drawn = drawn(WarningsAndErrors.markdown(entries, ZoneOffset.UTC));

        assertTrue(drawn.contains("Warning: UUIDs added to a component"), drawn);
        assertTrue(drawn.contains("Component 12884907155 [b1d4383c-3e51-54b8-9987-5cb677af3ab4] gained UUIDs"
                + " [045ce355-7f3a-5fce-8178-2e6c85c44d7c]: a public id <sharing> one_of_its *UUIDs* carried them."), drawn);
        assertTrue(drawn.contains("- a second line"), drawn);
        assertTrue(drawn.contains("1. and a third"), drawn);
    }

    @Test
    void aRepeatedAlertSaysHowOftenAndWhenLast() {
        AlertLog.Entry repeated = new AlertLog.Entry(NOON, NOON.plusSeconds(90), 3, AlertType.WARNING,
                "Stamp versions dropped", "Kept two.", "", false);
        String text = WarningsAndErrors.plainText(List.of(repeated), ZoneOffset.UTC);
        assertTrue(text.contains("2026-11-10 12:00:00, then 2 more times, the last at 12:01:30"), text);
    }

    @Test
    void anErrorShowsTheStartOfItsStackTrace() {
        AlertLog log = new AlertLog();
        log.record(AlertObject.makeError(new IllegalStateException("import failed")));
        String markdown = WarningsAndErrors.markdown(log.entries(), ZoneOffset.UTC);
        assertTrue(markdown.contains("### Error: IllegalStateException"), markdown);
        assertTrue(markdown.contains("~~~~\njava.lang.IllegalStateException: import failed"), markdown);
        long traceLines = markdown.lines().filter(line -> line.strip().startsWith("at ")).count();
        assertTrue(traceLines < WarningsAndErrors.TRACE_LINES, "the list shows only the start of a trace");
    }

    @Test
    void onlyTheLatestEntriesAreShownAndTheRestAreNamed() {
        List<AlertLog.Entry> entries = new ArrayList<>();
        for (int i = 0; i < WarningsAndErrors.SHOWN + 3; i++) {
            entries.add(entry(NOON.plusSeconds(i), AlertType.WARNING, "Advisory " + i, "", false));
        }
        String text = WarningsAndErrors.plainText(entries, ZoneOffset.UTC);
        assertFalse(text.contains("Advisory 2\n"));
        assertTrue(text.contains("Advisory 3\n"));
        assertTrue(text.contains("3 older entries are not shown. The file " + AlertLog.FILE_NAME), text);
    }

    @Test
    void theLandingPageCountsEachKindAndWhatIsNew() {
        List<AlertLog.Entry> entries = List.of(
                entry(NOON, AlertType.WARNING, "One", "", true),
                new AlertLog.Entry(NOON, NOON.plusSeconds(5), 3, AlertType.WARNING, "Two", "", "", false),
                entry(NOON, AlertType.ERROR, "Three", "", false));
        assertEquals("1 error, 4 warnings", WarningsAndErrors.summary(entries, 0), "a repeat is counted each time");
        assertEquals("1 error, 4 warnings (4 new)", WarningsAndErrors.summary(entries, 4));
    }
}
