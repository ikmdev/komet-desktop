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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The New-store folder-name proposal ({@link SelectDataSourceController#deriveStoreFolderName}):
 * the minimal name that stays unique while preserving a snapshot's date and time, without the
 * classifier, ending in the store-type suffix (ikmdev/komet-desktop#120).
 */
class SelectDataSourceControllerTest {

    @Test
    void inSessionMavenDownloadNameDerivesDateTimeAndStoreSuffix() {
        assertEquals("ike-starter-set-1-20260726-155158.sa",
                SelectDataSourceController.deriveStoreFolderName(
                        "ike-starter-set-1-20260726.155158-4-reasoned-pb", "sa"));
    }

    @Test
    void diskScannedZipNameDerivesTheSameProposal() {
        assertEquals("ike-starter-set-1-20260726-155158.sa",
                SelectDataSourceController.deriveStoreFolderName(
                        "ike-starter-set-1-20260726.155158-4-reasoned-pb.zip", "sa"));
    }

    @Test
    void legacyFlowSuffixedNameKeepsDateTimeAndDropsBuildCounter() {
        assertEquals("ike-starter-set-1-20260726-143839.sa",
                SelectDataSourceController.deriveStoreFolderName(
                        "ike-starter-set-1-20260726.143839-2-pb.zip", "sa"));
    }

    @Test
    void wordyBranchVersionKeepsItsWordsAndDateTime() {
        assertEquals("ike-starter-set-1-chronology-builder-20260724-000852.sa",
                SelectDataSourceController.deriveStoreFolderName(
                        "ike-starter-set-1-chronology-builder-20260724.000852-12-pb.zip", "sa"));
    }

    @Test
    void saveAsCopyCounterIsDroppedAlongWithTheBuildCounter() {
        assertEquals("ike-starter-set-1-chronology-builder-20260724-000852.sa",
                SelectDataSourceController.deriveStoreFolderName(
                        "ike-starter-set-1-chronology-builder-20260724.000852-12-2-pb.zip", "sa"));
    }

    @Test
    void classifierQualifiedChangesetNameLosesBothTails() {
        assertEquals("ike-changeset-2.0.0.sa",
                SelectDataSourceController.deriveStoreFolderName(
                        "ike-changeset-2.0.0-changeset-pb.zip", "sa"));
    }

    @Test
    void releaseVersionKeepsItsDotsReadable() {
        assertEquals("pizzakb-1.0.0.sa",
                SelectDataSourceController.deriveStoreFolderName("pizzakb-1.0.0-pb.zip", "sa"));
    }

    @Test
    void storeSuffixFollowsTheTargetStoreType() {
        assertEquals("ike-starter-set-1-20260726-155158.rkb",
                SelectDataSourceController.deriveStoreFolderName(
                        "ike-starter-set-1-20260726.155158-4-reasoned-pb", "rkb"));
    }

    @Test
    void nameThatIsNothingButClassifierTailsStaysRecognizable() {
        assertEquals("-pb.sa", SelectDataSourceController.deriveStoreFolderName("-pb.zip", "sa"));
    }

    @Test
    void availableNameIsReturnedAsIs(@TempDir Path solorRoot) {
        assertEquals("ike-starter-set-1-20260726-155158.sa",
                SelectDataSourceController.firstAvailableFolderName(solorRoot.toFile(),
                        "ike-starter-set-1-20260726-155158.sa"));
    }

    @Test
    void takenNameBumpsACounterBeforeTheStoreSuffix(@TempDir Path solorRoot) throws Exception {
        Files.createDirectory(solorRoot.resolve("ike-starter-set-1-20260726-155158.sa"));
        Files.createDirectory(solorRoot.resolve("ike-starter-set-1-20260726-155158-2.sa"));

        assertEquals("ike-starter-set-1-20260726-155158-3.sa",
                SelectDataSourceController.firstAvailableFolderName(solorRoot.toFile(),
                        "ike-starter-set-1-20260726-155158.sa"));
    }
}
