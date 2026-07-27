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

import dev.ikm.komet.pluginresolver.ArtifactCoordinates;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code ~/Solor} destination naming rule
 * ({@link MavenDataSourceDialogController#solorDestination}): names embed the resolved classifier
 * so differently-classified pulls of the same version never collide (ikmdev/komet-desktop#118),
 * while every PB file name keeps the {@code pb.zip} ending the providers'
 * {@code New*Controller.isValidDataLocation} predicates accept a changeset zip by.
 */
class MavenDataSourceDialogControllerTest {

    private static final Path SOLOR_ROOT = Path.of("solor-root");

    /**
     * Verbatim copy of the acceptance predicate shared by the {@code New*Controller}s of
     * {@code SpinedArrayProvider}, {@code ProviderEphemeral}, {@code MVStoreProvider}, and
     * Rocks KB's {@code RocksProvider} — the contract every PB destination name must satisfy.
     */
    private static boolean acceptedByNewControllers(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith("pb.zip")
                || (name.toLowerCase(Locale.ROOT).endsWith(".zip")
                        && name.toLowerCase(Locale.ROOT).contains("tink"));
    }

    @Test
    void pbNameAlreadyEndingInPbGetsBareZipSuffix() {
        Path destination = MavenDataSourceDialogController.solorDestination(SOLOR_ROOT,
                ProviderArtifactQualifier.Flow.PB,
                "ike-starter-set-1-chronology-builder-20260724.000852-12-reasoned-pb");

        assertEquals("ike-starter-set-1-chronology-builder-20260724.000852-12-reasoned-pb.zip",
                destination.getFileName().toString());
    }

    @Test
    void pbNameNotEndingInPbGetsTheFlowSuffixAppended() {
        Path destination = MavenDataSourceDialogController.solorDestination(SOLOR_ROOT,
                ProviderArtifactQualifier.Flow.PB, "ike-changeset-2.0.0-changeset");

        assertEquals("ike-changeset-2.0.0-changeset-pb.zip", destination.getFileName().toString());
    }

    @Test
    void bareClassifierPbDegeneratesToThePreClassifierName() {
        Path destination = MavenDataSourceDialogController.solorDestination(SOLOR_ROOT,
                ProviderArtifactQualifier.Flow.PB, "example-plugin-2.0.0-pb");

        assertEquals("example-plugin-2.0.0-pb.zip", destination.getFileName().toString());
    }

    @Test
    void handTypedSaveAsNameStillLandsInTheAcceptedShape() {
        Path destination = MavenDataSourceDialogController.solorDestination(SOLOR_ROOT,
                ProviderArtifactQualifier.Flow.PB, "my-renamed-download");

        assertEquals("my-renamed-download-pb.zip", destination.getFileName().toString());
        assertTrue(acceptedByNewControllers(destination.getFileName().toString()));
    }

    @Test
    void everyPbCandidateClassifierYieldsANameTheNewControllersAccept() {
        for (String classifier : ProviderArtifactQualifier.classifierCandidates(ProviderArtifactQualifier.Flow.PB)) {
            Path destination = MavenDataSourceDialogController.solorDestination(SOLOR_ROOT,
                    ProviderArtifactQualifier.Flow.PB, "example-plugin-1.0.0-" + classifier);

            assertTrue(acceptedByNewControllers(destination.getFileName().toString()),
                    "classifier " + classifier + " produced a name the New controllers would not list: "
                            + destination.getFileName());
        }
    }

    @Test
    void differentClassifiersOfTheSameVersionNeverCollide() {
        Set<Path> destinations = new HashSet<>();
        for (String classifier : ProviderArtifactQualifier.classifierCandidates(ProviderArtifactQualifier.Flow.PB)) {
            String artifactName = MavenDataSourceDialogController.classifiedArtifactName(
                    new ArtifactCoordinates("network.ike.foundation", "example-plugin"), "1.0.0", classifier);
            destinations.add(MavenDataSourceDialogController.solorDestination(SOLOR_ROOT,
                    ProviderArtifactQualifier.Flow.PB, artifactName));
        }

        assertEquals(ProviderArtifactQualifier.classifierCandidates(ProviderArtifactQualifier.Flow.PB).size(),
                destinations.size());
    }

    @Test
    void classifiedArtifactNameIsArtifactIdFileVersionClassifier() {
        String artifactName = MavenDataSourceDialogController.classifiedArtifactName(
                new ArtifactCoordinates("network.ike.foundation", "ike-starter-set"),
                "1-chronology-builder-20260724.000852-12", "unreasoned-pb");

        assertEquals("ike-starter-set-1-chronology-builder-20260724.000852-12-unreasoned-pb", artifactName);
    }

    @Test
    void mostRecentVersionPicksTheNewestStampAcrossSnapshotLines() {
        // The reported case (ikmdev/komet-desktop#121): lexicographically, the two-day-old
        // chronology-builder build sorts last — the stamp says otherwise.
        List<String> versions = List.of(
                "1-20260726.143839-2",
                "1-20260726.152404-3",
                "1-20260726.155158-4",
                "1-20260727.032644-5",
                "1-chronology-builder-20260724.000852-12");

        assertEquals("1-20260727.032644-5",
                MavenDataSourceDialogController.mostRecentVersion(versions));
    }

    @Test
    void mostRecentVersionBreaksEqualStampsByBuildNumber() {
        assertEquals("1-20260726.155158-12",
                MavenDataSourceDialogController.mostRecentVersion(List.of(
                        "1-20260726.155158-12",
                        "1-20260726.155158-4")));
    }

    @Test
    void mostRecentVersionFallsBackToLastWhenNothingIsStamped() {
        assertEquals("20250901",
                MavenDataSourceDialogController.mostRecentVersion(List.of("20250827", "20250901")));
    }

    @Test
    void mostRecentVersionPrefersStampedOverUnstampedVersions() {
        assertEquals("1-20260727.032644-5",
                MavenDataSourceDialogController.mostRecentVersion(List.of(
                        "1-20260727.032644-5",
                        "20991231")));
    }

    @Test
    void rememberedRepositorySelectionIsRestoredVerbatim() {
        assertEquals("https://nexus.example.com/repository/custom/",
                MavenDataSourceDialogController.repositoryUrlToRestore(
                        Optional.of("https://nexus.example.com/repository/custom/")));
        assertEquals("Local repository (~/.m2)",
                MavenDataSourceDialogController.repositoryUrlToRestore(
                        Optional.of("Local repository (~/.m2)")));
    }

    @Test
    void absentOrBlankRememberedSelectionFallsBackToTheDefaultRepository() {
        assertEquals("https://nexus.tinkar.org/repository/ike-restricted/",
                MavenDataSourceDialogController.repositoryUrlToRestore(Optional.empty()));
        assertEquals("https://nexus.tinkar.org/repository/ike-restricted/",
                MavenDataSourceDialogController.repositoryUrlToRestore(Optional.of("  ")));
    }

    @Test
    void rememberedRetiredCentralSelectionMigratesToTheDefaultRepository() {
        // Central was a built-in choice before IKE-Network/ike-issues#958; a ComboBox happily
        // displays a value absent from its items list, so without this migration a persisted
        // Central selection would resurface forever.
        assertEquals("https://nexus.tinkar.org/repository/ike-restricted/",
                MavenDataSourceDialogController.repositoryUrlToRestore(
                        Optional.of("https://repo.maven.apache.org/maven2/")));
    }

    @Test
    void saAndRocksDestinationsAreTheArtifactNameVerbatim() {
        assertEquals("example-data-1.0.0-reasoned-sa",
                MavenDataSourceDialogController.solorDestination(SOLOR_ROOT,
                        ProviderArtifactQualifier.Flow.SA, "example-data-1.0.0-reasoned-sa")
                        .getFileName().toString());
        assertEquals("example-data-1.0.0-rkb",
                MavenDataSourceDialogController.solorDestination(SOLOR_ROOT,
                        ProviderArtifactQualifier.Flow.ROCKS, "example-data-1.0.0-rkb")
                        .getFileName().toString());
    }
}
