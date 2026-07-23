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
package dev.ikm.komet.desktop.datasource.maven;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderArtifactQualifierTest {

    @Test
    void newControllersOfAnyBackendOfferThePbFlow() {
        assertEquals(Optional.of(ProviderArtifactQualifier.Flow.PB), ProviderArtifactQualifier.flowFor("New MV Store"));
        assertEquals(Optional.of(ProviderArtifactQualifier.Flow.PB), ProviderArtifactQualifier.flowFor("New SpinedArrayStore"));
        assertEquals(Optional.of(ProviderArtifactQualifier.Flow.PB), ProviderArtifactQualifier.flowFor("New Rocks KB"));
    }

    @Test
    void spinedArrayOpenControllerOffersTheSaFlow() {
        assertEquals(Optional.of(ProviderArtifactQualifier.Flow.SA), ProviderArtifactQualifier.flowFor("Open SpinedArrayStore"));
    }

    @Test
    void rocksOpenControllerOffersTheRocksFlow() {
        assertEquals(Optional.of(ProviderArtifactQualifier.Flow.ROCKS), ProviderArtifactQualifier.flowFor("Open Rocks KB"));
    }

    @Test
    void mvStoreOpenControllerOffersNoFlow() {
        assertTrue(ProviderArtifactQualifier.flowFor("Open MV Store").isEmpty());
    }

    @Test
    void unrecognizedControllersOfferNoFlow() {
        assertTrue(ProviderArtifactQualifier.flowFor("Websocket").isEmpty());
        assertTrue(ProviderArtifactQualifier.flowFor("Load Ephemeral Store").isEmpty());
    }

    @Test
    void classifierCandidatesForPbTryReasonedVariantsBeforeTheGeneralOnesThenChangeset() {
        assertEquals(List.of("reasoned-pb", "unreasoned-pb", "pb", "changeset"),
                ProviderArtifactQualifier.classifierCandidates(ProviderArtifactQualifier.Flow.PB));
    }

    @Test
    void pickBestClassifierFallsBackToChangesetWhenThatsAllThisArtifactPublishes() {
        // Real shape confirmed against a live artifact, 2026-07-22:
        // network.ike.foundation:ike-changeset publishes only a "changeset"-classified zip —
        // semantically the same protobuf-changeset payload the PB flow expects.
        assertEquals(Optional.of("changeset"), ProviderArtifactQualifier.pickBestClassifier(
                ProviderArtifactQualifier.Flow.PB, Set.of("changeset")));
    }

    @Test
    void classifierCandidatesForSaTryTheDominantInventoriedConventionBeforeTheRarerOnes() {
        assertEquals(List.of("reasoned-sa", "unreasoned-sa", "spined-array", "sa"),
                ProviderArtifactQualifier.classifierCandidates(ProviderArtifactQualifier.Flow.SA));
    }

    @Test
    void classifierCandidatesForRocksIsThePlaceholder() {
        assertEquals(List.of("rkb"), ProviderArtifactQualifier.classifierCandidates(ProviderArtifactQualifier.Flow.ROCKS));
    }

    @Test
    void pickBestClassifierPrefersReasonedOverUnreasonedWhenBothAreReallyPublished() {
        assertEquals(Optional.of("reasoned-sa"), ProviderArtifactQualifier.pickBestClassifier(
                ProviderArtifactQualifier.Flow.SA, Set.of("unreasoned-sa", "reasoned-sa")));
    }

    @Test
    void pickBestClassifierFallsBackToARarerRealClassifierWhenTheDominantOneIsAbsent() {
        assertEquals(Optional.of("spined-array"), ProviderArtifactQualifier.pickBestClassifier(
                ProviderArtifactQualifier.Flow.SA, Set.of("spined-array")));
    }

    @Test
    void pickBestClassifierIsEmptyWhenNoRealClassifierMatchesTheFlow() {
        assertTrue(ProviderArtifactQualifier.pickBestClassifier(
                ProviderArtifactQualifier.Flow.SA, Set.of("reasoned-pb", "unreasoned-pb")).isEmpty());
        assertTrue(ProviderArtifactQualifier.pickBestClassifier(
                ProviderArtifactQualifier.Flow.PB, Set.of()).isEmpty());
    }
}
