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

import dev.ikm.komet.desktop.LaunchOptions.Option;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolution of the command-line launch options (IKE-Network/ike-issues#1139).
 */
class LaunchOptionsTest {

    private static final UnaryOperator<String> NONE = name -> null;

    @TempDir
    Path tempDir;

    @Test
    void programArgumentsAreRead() {
        LaunchOptions options = LaunchOptions.resolve(
                Map.of("kb", "SNOMED Rocks", "user", "Gretel", "password-file", "/tmp/pw"), List.of(), NONE);

        assertEquals(Optional.of("SNOMED Rocks"), options.get(Option.KB));
        assertEquals(Optional.of("Gretel"), options.get(Option.USER));
        assertEquals(Optional.of("/tmp/pw"), options.get(Option.PASSWORD_FILE));
    }

    @Test
    void systemPropertiesAreTheFallback() {
        Map<String, String> properties = Map.of("komet.kb", "/data/kb", "komet.user", "Gretel");

        LaunchOptions options = LaunchOptions.resolve(Map.of(), List.of(), properties::get);

        assertEquals(Optional.of("/data/kb"), options.get(Option.KB));
        assertEquals(Optional.of("Gretel"), options.get(Option.USER));
        assertEquals(Optional.empty(), options.get(Option.PASSWORD_FILE));
    }

    @Test
    void aProgramArgumentWinsOverItsProperty() {
        LaunchOptions options = LaunchOptions.resolve(Map.of("kb", "from-argument"), List.of(),
                Map.of("komet.kb", "from-property")::get);

        assertEquals(Optional.of("from-argument"), options.get(Option.KB));
    }

    @Test
    void blankValuesAreAbsentAndValuesAreStripped() {
        LaunchOptions options = LaunchOptions.resolve(Map.of("kb", "  ", "user", " Gretel "), List.of(),
                Map.of("komet.kb", "")::get);

        assertEquals(Optional.empty(), options.get(Option.KB));
        assertEquals(Optional.of("Gretel"), options.get(Option.USER));
    }

    @Test
    void aPasswordArgumentIsNeverRead() {
        LaunchOptions options = LaunchOptions.resolve(Map.of("user", "Gretel", "password", "secret"), List.of(), NONE);

        assertEquals(Optional.empty(), options.password(NONE));
    }

    @Test
    void thePasswordComesFromTheEnvironmentFirst() throws IOException {
        Path file = Files.writeString(tempDir.resolve("pw"), "from-file\n");
        LaunchOptions options = LaunchOptions.resolve(Map.of("password-file", file.toString()), List.of(), NONE);

        assertEquals(Optional.of("from-env"),
                options.password(Map.of(LaunchOptions.PASSWORD_ENVIRONMENT_VARIABLE, "from-env")::get));
    }

    @Test
    void thePasswordFileGivesItsFirstLine() throws IOException {
        Path file = Files.writeString(tempDir.resolve("pw"), "Gretel\nignored\n");
        LaunchOptions options = LaunchOptions.resolve(Map.of("password-file", file.toString()), List.of(), NONE);

        assertEquals(Optional.of("Gretel"), options.password(NONE));
    }

    @Test
    void anUnreadablePasswordFileGivesNoPassword() {
        LaunchOptions options = LaunchOptions.resolve(
                Map.of("password-file", tempDir.resolve("missing").toString()), List.of(), NONE);

        assertEquals(Optional.empty(), options.password(NONE));
    }

    @Test
    void noRestoreIsOffByDefault() {
        assertFalse(LaunchOptions.resolve(Map.of(), List.of(), NONE).noRestore());
    }

    @Test
    void noRestoreFlagSkipsReopening() {
        assertTrue(LaunchOptions.resolve(Map.of(), List.of(LaunchOptions.NO_RESTORE_ARGUMENT), NONE).noRestore());
    }

    @Test
    void noRestorePropertySkipsReopening() {
        assertTrue(LaunchOptions.resolve(Map.of(), List.of(),
                Map.of(LaunchOptions.NO_RESTORE_PROPERTY, "true")::get).noRestore());
        assertFalse(LaunchOptions.resolve(Map.of(), List.of(),
                Map.of(LaunchOptions.NO_RESTORE_PROPERTY, "false")::get).noRestore());
    }
}
