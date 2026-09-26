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

import dev.ikm.komet.desktop.KnowledgeBaseResolver.Candidate;
import dev.ikm.komet.desktop.KnowledgeBaseResolver.Found;
import dev.ikm.komet.desktop.KnowledgeBaseResolver.Resolution;
import dev.ikm.komet.desktop.KnowledgeBaseResolver.Unresolved;
import dev.ikm.tinkar.common.service.DataUriOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolution of a {@code --kb} knowledge base against the picker's entries
 * (IKE-Network/ike-issues#1139).
 */
class KnowledgeBaseResolverTest {

    @TempDir
    Path home;

    private Path solor;
    private DataUriOption rocksOption;
    private DataUriOption spinedOption;
    private List<Candidate> candidates;

    @BeforeEach
    void knowledgeBases() throws IOException {
        solor = Files.createDirectories(home.resolve("Solor"));
        Path rocks = Files.createDirectories(solor.resolve("SNOMED Rocks"));
        Path spined = Files.createDirectories(solor.resolve("starter-spined"));
        Files.createDirectories(solor.resolve("not-a-kb"));
        rocksOption = new DataUriOption("SNOMED Rocks", rocks.toUri());
        spinedOption = new DataUriOption("starter-spined", spined.toUri());
        candidates = List.of(
                new Candidate("Open Rocks KB", List.of(rocksOption)),
                new Candidate("Open SpinedArrayStore", List.of(spinedOption)),
                new Candidate("Open websocket", List.of(new DataUriOption("remote", URI.create("ws://host:8080")))));
    }

    @Test
    void aFolderNameUnderSolorResolves() {
        Resolution resolution = KnowledgeBaseResolver.resolve("SNOMED Rocks", solor, home, candidates);

        assertEquals(new Found("Open Rocks KB", rocksOption), resolution);
    }

    @Test
    void anAbsolutePathResolves() {
        Resolution resolution = KnowledgeBaseResolver.resolve(
                solor.resolve("starter-spined").toString(), solor, home, candidates);

        assertEquals(new Found("Open SpinedArrayStore", spinedOption), resolution);
    }

    @Test
    void aHomeRelativePathResolves() {
        Resolution resolution = KnowledgeBaseResolver.resolve("~/Solor/SNOMED Rocks/", solor, home, candidates);

        assertEquals(new Found("Open Rocks KB", rocksOption), resolution);
    }

    @Test
    void aMissingFolderIsUnresolved() {
        Resolution resolution = KnowledgeBaseResolver.resolve("absent", solor, home, candidates);

        Unresolved unresolved = assertInstanceOf(Unresolved.class, resolution);
        assertTrue(unresolved.message().startsWith("No knowledge base folder at"), unresolved.message());
    }

    @Test
    void aFolderNoProviderListsIsUnresolved() {
        Resolution resolution = KnowledgeBaseResolver.resolve("not-a-kb", solor, home, candidates);

        Unresolved unresolved = assertInstanceOf(Unresolved.class, resolution);
        assertTrue(unresolved.message().contains("is not a knowledge base any provider opens"), unresolved.message());
    }

    @Test
    void aFolderTwoProvidersListIsUnresolved() {
        List<Candidate> overlapping = List.of(
                new Candidate("Open Rocks KB", List.of(rocksOption)),
                new Candidate("Open MV Store", List.of(rocksOption)));

        Resolution resolution = KnowledgeBaseResolver.resolve("SNOMED Rocks", solor, home, overlapping);

        Unresolved unresolved = assertInstanceOf(Unresolved.class, resolution);
        assertTrue(unresolved.message().contains("Open Rocks KB, Open MV Store"), unresolved.message());
    }
}
