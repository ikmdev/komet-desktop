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

import dev.ikm.tinkar.importer.registry.ChangeSetLoad;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The headless change-set load ({@code dev-ike-dashboard}, decision 20): what it will not run
 * without, before it opens any knowledge base, and the folders it reads.
 */
class ChangeSetLoadCommandTest {

    @TempDir
    Path tempDir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... arguments) {
        return ChangeSetLoadCommand.run(LaunchOptions.fromArguments(arguments, name -> null),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void aLoadNeedsAKnowledgeBase() {
        assertEquals(ChangeSetLoadCommand.NOT_RUN, run("--load-change-sets"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--kb=<store>"), err.toString(StandardCharsets.UTF_8));
        assertEquals("", out.toString(StandardCharsets.UTF_8), "no report");
    }

    @Test
    void theFoldersAreTheWritersKnowledgeAndInstanceFolders() throws Exception {
        Path store = tempDir.resolve("kb");
        Files.createDirectories(store.resolve("changeSets/src/main/resources"));
        Files.createDirectories(store.resolve("instanceChangeSets"));
        Files.createDirectories(store.resolve("ephemeralChangeSets"));
        assertEquals(List.of(store.resolve("changeSets/src/main/resources"), store.resolve("instanceChangeSets")),
                ChangeSetLoad.folders(store), "knowledge first, then instance; never ephemeral");
        assertEquals(List.of(), ChangeSetLoad.folders(tempDir.resolve("no such store")), "a store with no folders loads nothing");
    }

    @Test
    void theReportIsBetweenItsMarkerLines() {
        assertTrue(ChangeSetLoadCommand.REPORT_START.startsWith("----- ") && ChangeSetLoadCommand.REPORT_START.endsWith(" -----"));
        assertEquals(ChangeSetUpgradeCommand.REPORT_END, ChangeSetLoadCommand.REPORT_END, "the same end line as the upgrade's");
    }
}
