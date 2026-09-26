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
package dev.ikm.komet.desktop;

import dev.ikm.komet.desktop.OpenWindows.Entry;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The stored form of the windows open at quit (IKE-Network/ike-issues#1151).
 */
class OpenWindowsTest {

    private static final UUID TOPIC = UUID.fromString("d840d8af-0062-4af7-b1f8-d19ea592c3a9");

    @Test
    void aJournalRoundTrips() {
        Entry journal = Entry.journal(TOPIC);

        assertEquals("JOURNAL|" + TOPIC, OpenWindows.encode(journal));
        assertEquals(Optional.of(journal), OpenWindows.decode(OpenWindows.encode(journal)));
    }

    @Test
    void aKlEditorWindowRoundTripsWithItsBounds() {
        Entry editor = Entry.klEditor("Concept (2)", true, new Rectangle2D(10, 20, 1200, 800));

        assertEquals(Optional.of(editor), OpenWindows.decode(OpenWindows.encode(editor)));
    }

    @Test
    void aLayoutTitleMayContainTheSeparator() {
        Entry editor = Entry.klEditor("Drugs | dose forms", false, new Rectangle2D(0, 0, 900, 700));

        assertEquals(Optional.of(editor), OpenWindows.decode(OpenWindows.encode(editor)));
    }

    @Test
    void entriesThatDoNotDecodeAreSkippedAndOrderIsKept() {
        Entry first = Entry.journal(TOPIC);
        Entry last = Entry.klEditor("Pattern", true, new Rectangle2D(5, 5, 600, 400));
        List<String> stored = List.of(OpenWindows.encode(first), "UNKNOWN|x", "JOURNAL|not-a-uuid",
                "KL_EDITOR|true|1|2", OpenWindows.encode(last));

        assertEquals(List.of(first, last), OpenWindows.decodeAll(stored));
    }

    @Test
    void encodeAllKeepsBackToFrontOrder() {
        Entry back = Entry.journal(TOPIC);
        Entry front = Entry.journal(UUID.fromString("a17142f5-14c9-49bd-8d83-8f3890f90e3c"));

        assertEquals(List.of(back, front), OpenWindows.decodeAll(OpenWindows.encodeAll(List.of(back, front))));
    }

    @Test
    void landingPageBoundsRoundTrip() {
        Rectangle2D bounds = new Rectangle2D(40, 60, 1035, 850);

        assertEquals(Optional.of(bounds), OpenWindows.decodeBounds(OpenWindows.encodeBounds(bounds)));
    }

    @Test
    void boundsWithoutAreaOrMalformedAreEmpty() {
        assertEquals(Optional.empty(), OpenWindows.decodeBounds("0|0|0|0"));
        assertEquals(Optional.empty(), OpenWindows.decodeBounds("1|2|3"));
        assertEquals(Optional.empty(), OpenWindows.decodeBounds("a|b|c|d"));
    }
}
