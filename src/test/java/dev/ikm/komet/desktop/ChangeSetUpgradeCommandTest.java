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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The headless change-set upgrade's refusals: what it will not run without, before it opens any
 * knowledge base (IKE-Network/ike-issues#1313).
 */
class ChangeSetUpgradeCommandTest {

    @TempDir
    Path tempDir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... arguments) {
        return ChangeSetUpgradeCommand.run(LaunchOptions.fromArguments(arguments, name -> null),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void anUpgradeNeedsAKnowledgeBase() {
        assertEquals(ChangeSetUpgradeCommand.NOT_RUN, run("--upgrade-change-sets"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--kb=<store>"), err.toString(StandardCharsets.UTF_8));
        assertEquals("", out.toString(StandardCharsets.UTF_8), "no report");
    }

    @Test
    void aFolderThatIsNotThereIsRefused() {
        Path missing = tempDir.resolve("missing");
        assertEquals(ChangeSetUpgradeCommand.NOT_RUN, run("--kb=ike-kb", "--upgrade-change-sets=" + missing));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("No change-set folder at " + missing));
    }

    @Test
    void theDefaultFolderIsWhereTheStoresWriterWrites() {
        assertEquals(Path.of("/kb/changeSets/src/main/resources"), ChangeSetUpgradeCommand.changeSetFolder(Path.of("/kb")));
    }
}
