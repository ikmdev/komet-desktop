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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editable classifier selection: what the user typed, in preference order, resolved against
 * the classifiers a repository actually publishes (ikmdev/komet-desktop#117).
 */
class ClassifierSelectionTest {

    private static final Set<String> PUBLISHED =
            Set.of("reasoned-pb", "unreasoned-pb", "pb", "cyclonedx", "sources");

    @Test
    void flowDefaultsAreTheFlowsOwnCandidates() {
        ClassifierSelection selection = ClassifierSelection.ofFlowDefaults(ProviderArtifactQualifier.Flow.PB);

        assertEquals(ProviderArtifactQualifier.classifierCandidates(ProviderArtifactQualifier.Flow.PB),
                selection.patterns());
    }

    @Test
    void parseAcceptsCommaPipeAndWhitespaceSeparators() {
        assertEquals(List.of("pb", "reasoned-pb"), ClassifierSelection.parse("pb, reasoned-pb").patterns());
        assertEquals(List.of("pb", "reasoned-pb"), ClassifierSelection.parse("pb|reasoned-pb").patterns());
        assertEquals(List.of("pb", "reasoned-pb"), ClassifierSelection.parse("pb  reasoned-pb").patterns());
    }

    @Test
    void parseDropsBlanksAndDuplicatesButKeepsOrder() {
        ClassifierSelection selection = ClassifierSelection.parse("  reasoned-pb, , pb , reasoned-pb ");

        assertEquals(List.of("reasoned-pb", "pb"), selection.patterns());
    }

    @Test
    void parseOfBlankTextIsEmptySoTheCallerCanSubstituteFlowDefaults() {
        assertTrue(ClassifierSelection.parse(null).isEmpty());
        assertTrue(ClassifierSelection.parse("   ").isEmpty());
        assertFalse(ClassifierSelection.parse("pb").isEmpty());
    }

    @Test
    void displayTextRoundTripsThroughParse() {
        ClassifierSelection selection = ClassifierSelection.parse("reasoned-pb|pb");

        assertEquals(selection.patterns(), ClassifierSelection.parse(selection.displayText()).patterns());
    }

    @Test
    void resolveReturnsMatchesInPatternPreferenceOrder() {
        ClassifierSelection selection = ClassifierSelection.parse("pb, reasoned-pb");

        // Preference follows the patterns as typed, not the published set's own ordering.
        assertEquals(List.of("pb", "reasoned-pb"), selection.resolve(PUBLISHED));
    }

    @Test
    void resolveIgnoresPatternsTheRepositoryDoesNotPublish() {
        ClassifierSelection selection = ClassifierSelection.parse("does-not-exist, reasoned-pb");

        assertEquals(List.of("reasoned-pb"), selection.resolve(PUBLISHED));
    }

    @Test
    void resolveIsEmptyWhenNothingMatches() {
        assertTrue(ClassifierSelection.parse("nope, also-nope").resolve(PUBLISHED).isEmpty());
    }

    @Test
    void wildcardExpandsOnlyAgainstPublishedClassifiers() {
        ClassifierSelection selection = ClassifierSelection.parse("reasoned-*");

        assertEquals(List.of("reasoned-pb"), selection.resolve(PUBLISHED));
    }

    @Test
    void wildcardMatchingSeveralReturnsThemAllSoTheCallerCanReportTheAmbiguity() {
        ClassifierSelection selection = ClassifierSelection.parse("*-pb");

        // Alphabetical within one pattern, so the same repository state always resolves alike.
        assertEquals(List.of("reasoned-pb", "unreasoned-pb"), selection.resolve(PUBLISHED));
    }

    @Test
    void bareWildcardMatchesEverythingPublished() {
        assertEquals(5, ClassifierSelection.parse("*").resolve(PUBLISHED).size());
    }

    @Test
    void aClassifierMatchedByAnEarlierPatternIsNotRepeatedByALaterOne() {
        ClassifierSelection selection = ClassifierSelection.parse("reasoned-pb, *-pb");

        assertEquals(List.of("reasoned-pb", "unreasoned-pb"), selection.resolve(PUBLISHED));
    }

    @Test
    void wildcardIsNotTreatedAsARegularExpression() {
        // '.' is literal in a glob: it must not match the 'x' in "pbx".
        assertTrue(ClassifierSelection.parse("p.").resolve(Set.of("pb", "px")).isEmpty());
        assertEquals(List.of("p.b"), ClassifierSelection.parse("p.*").resolve(Set.of("p.b", "pxb")));
    }

    @Test
    void literalsExcludeWildcardsSoASearchCanBeScopedByThem() {
        ClassifierSelection selection = ClassifierSelection.parse("reasoned-pb, reasoned-*, pb");

        assertEquals(List.of("reasoned-pb", "pb"), selection.literals());
    }

    @Test
    void literalsIsEmptyWhenEveryPatternIsAWildcard() {
        assertTrue(ClassifierSelection.parse("reasoned-*, *-pb").literals().isEmpty());
    }

    @Test
    void pickBestIsTheMostPreferredMatch() {
        assertEquals("pb", ClassifierSelection.parse("pb, reasoned-pb").pickBest(PUBLISHED).orElseThrow());
        assertTrue(ClassifierSelection.parse("nope").pickBest(PUBLISHED).isEmpty());
    }

    @Test
    void defaultSelectionStillPrefersReasonedPbForThePbFlow() {
        // The regression this whole field exists to prevent: untouched, behavior is unchanged.
        ClassifierSelection defaults = ClassifierSelection.ofFlowDefaults(ProviderArtifactQualifier.Flow.PB);

        assertEquals(ProviderArtifactQualifier.pickBestClassifier(ProviderArtifactQualifier.Flow.PB, PUBLISHED),
                defaults.pickBest(PUBLISHED));
    }
}
