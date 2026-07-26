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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which classifiers to look for, in preference order — the flow's own candidates by default, or
 * whatever the user typed in their place (ikmdev/komet-desktop#117).
 *
 * <p>A flow's built-in candidate list cannot anticipate every classifier a repository publishes,
 * and when it misses one the artifact is simply invisible: present, correct, and unreachable with
 * no way to say what to look for. A selection is the editable form of that list.
 *
 * <p>A pattern is either a literal classifier ({@code reasoned-pb}) or a glob containing
 * {@code *} ({@code reasoned-*}). Order is preference order: {@link #resolve} returns matches in
 * the order their patterns appear, so an earlier pattern always wins over a later one. Wildcards
 * only ever expand against classifiers a repository actually publishes, so a pattern can never
 * widen into a request for something that isn't there.
 *
 * @param patterns the classifier patterns, in preference order; never {@code null}
 */
public record ClassifierSelection(List<String> patterns) {

    /** Characters that separate patterns in the edited text: comma, pipe, or whitespace. */
    private static final String PATTERN_SEPARATORS = "[,|\\s]+";

    /**
     * Canonicalizes the pattern list: order preserved, blanks dropped, duplicates removed.
     *
     * @param patterns the classifier patterns
     * @throws NullPointerException if {@code patterns} is {@code null}
     */
    public ClassifierSelection {
        Objects.requireNonNull(patterns, "patterns");
        patterns = List.copyOf(new LinkedHashSet<>(patterns.stream()
                .map(String::strip)
                .filter(pattern -> !pattern.isEmpty())
                .toList()));
    }

    /**
     * The selection a flow starts with — {@link ProviderArtifactQualifier#classifierCandidates}.
     *
     * @param flow the flow
     * @return the flow's default selection
     */
    public static ClassifierSelection ofFlowDefaults(ProviderArtifactQualifier.Flow flow) {
        return new ClassifierSelection(ProviderArtifactQualifier.classifierCandidates(flow));
    }

    /**
     * Parses edited text into a selection, accepting comma-, pipe-, or whitespace-separated
     * patterns so a user needn't guess which separator this field wants.
     *
     * @param text the edited text; may be {@code null} or blank, which parses to an empty selection
     * @return the parsed selection
     */
    public static ClassifierSelection parse(String text) {
        if (text == null || text.isBlank()) {
            return new ClassifierSelection(List.of());
        }
        return new ClassifierSelection(List.of(text.strip().split(PATTERN_SEPARATORS)));
    }

    /**
     * Whether this selection names nothing — the caller substitutes the flow's defaults.
     *
     * @return {@code true} if there are no patterns
     */
    public boolean isEmpty() {
        return patterns.isEmpty();
    }

    /**
     * The canonical text for the editable field.
     *
     * @return the patterns, comma-separated
     */
    public String displayText() {
        return String.join(", ", patterns);
    }

    /**
     * The literal (non-wildcard) patterns — what a repository <em>search</em> can be scoped by,
     * since a search filters on exact classifier names and cannot evaluate a glob.
     *
     * @return the literal patterns, in preference order
     */
    public List<String> literals() {
        return patterns.stream().filter(pattern -> pattern.indexOf('*') < 0).toList();
    }

    /**
     * The classifiers among {@code availableClassifiers} this selection names, in preference
     * order: each pattern contributes its matches before the next pattern is considered, and a
     * classifier already contributed is not repeated. A wildcard's own matches are ordered
     * alphabetically so the same repository state always resolves the same way.
     *
     * @param availableClassifiers the classifiers actually published for one specific version
     * @return the matching classifiers, most-preferred first; empty if none match
     * @throws NullPointerException if {@code availableClassifiers} is {@code null}
     */
    public List<String> resolve(Collection<String> availableClassifiers) {
        Objects.requireNonNull(availableClassifiers, "availableClassifiers");
        Set<String> available = new TreeSet<>(availableClassifiers);
        List<String> resolved = new ArrayList<>();
        for (String pattern : patterns) {
            for (String classifier : available) {
                if (matches(pattern, classifier) && !resolved.contains(classifier)) {
                    resolved.add(classifier);
                }
            }
        }
        return List.copyOf(resolved);
    }

    /**
     * The single classifier to download: the most-preferred match.
     *
     * @param availableClassifiers the classifiers actually published for one specific version
     * @return the classifier to use, or empty if none match
     */
    public Optional<String> pickBest(Collection<String> availableClassifiers) {
        return resolve(availableClassifiers).stream().findFirst();
    }

    /**
     * Whether {@code classifier} matches {@code pattern}, where {@code *} stands for any run of
     * characters (including none) and every other character matches itself literally.
     *
     * @param pattern the pattern
     * @param classifier the classifier to test
     * @return {@code true} if the pattern matches
     */
    private static boolean matches(String pattern, String classifier) {
        if (pattern.indexOf('*') < 0) {
            return pattern.equals(classifier);
        }
        StringBuilder regex = new StringBuilder(pattern.length() + 8);
        int segmentStart = 0;
        for (int i = 0; i < pattern.length(); i++) {
            if (pattern.charAt(i) == '*') {
                regex.append(java.util.regex.Pattern.quote(pattern.substring(segmentStart, i)))
                        .append(".*");
                segmentStart = i + 1;
            }
        }
        regex.append(java.util.regex.Pattern.quote(pattern.substring(segmentStart)));
        return classifier.matches(regex.toString());
    }
}
