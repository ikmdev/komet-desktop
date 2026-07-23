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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether — and how — a "download from Maven" option should be offered for a given
 * {@code DataServiceController}, matched by its {@code controllerName()} rather than its
 * class. Class-based matching isn't possible in general: Rocks KB is loaded through the
 * dynamic plugin layer ({@code IkeServiceManager}), not a {@code komet-desktop} compile
 * dependency, so its controller classes aren't on this module's classpath to reference.
 *
 * <p>Scope is deliberately narrow, matching real existing artifact conventions
 * (`ike:kb-assemble`'s {@code ArtifactInput.Role}) rather than an invented one:
 * <ul>
 *     <li>Any {@code "New "}-prefixed controller (any backend) offers the {@link Flow#PB}
 *     flow — a protobuf changeset zip, backend-independent.</li>
 *     <li>The SpinedArray {@code Open*Controller} offers the {@link Flow#SA} flow, and the
 *     Rocks KB {@code Open*Controller} offers the {@link Flow#ROCKS} flow — both a pre-built
 *     store snapshot unpacked as the base layer. No equivalent snapshot convention exists for
 *     MV Store today, so its {@code Open*Controller} offers nothing.</li>
 * </ul>
 */
public final class ProviderArtifactQualifier {

    /**
     * The exact {@code controllerName()} of SpinedArray's open controller
     * ({@code dev.ikm.tinkar.provider.spinedarray.SpinedArrayProvider.OpenController
     * .CONTROLLER_NAME}, confirmed verbatim against its source).
     */
    private static final String SPINED_ARRAY_OPEN_CONTROLLER_NAME = "Open SpinedArrayStore";

    /**
     * The exact {@code controllerName()} of Rocks KB's open controller, confirmed verbatim
     * against its source.
     */
    private static final String ROCKS_OPEN_CONTROLLER_NAME = "Open Rocks KB";

    private static final String NEW_CONTROLLER_PREFIX = "New ";

    private ProviderArtifactQualifier() {
    }

    /** Which kind of Maven-download flow applies to a controller, if any. */
    public enum Flow {
        /** A protobuf changeset zip, placed as-is under {@code ~/Solor}. */
        PB,
        /** A SpinedArray store snapshot, unpacked as a store directory under {@code ~/Solor}. */
        SA,
        /** A Rocks KB store snapshot, unpacked as a store directory under {@code ~/Solor}. */
        ROCKS
    }

    /**
     * Decides which flow, if any, applies to a controller.
     *
     * @param controllerName the controller's {@code controllerName()}
     * @return the applicable flow, or empty if this controller offers no Maven-download option
     * @throws NullPointerException if {@code controllerName} is {@code null}
     */
    public static Optional<Flow> flowFor(String controllerName) {
        Objects.requireNonNull(controllerName, "controllerName");
        if (controllerName.startsWith(NEW_CONTROLLER_PREFIX)) {
            return Optional.of(Flow.PB);
        }
        if (SPINED_ARRAY_OPEN_CONTROLLER_NAME.equals(controllerName)) {
            return Optional.of(Flow.SA);
        }
        if (ROCKS_OPEN_CONTROLLER_NAME.equals(controllerName)) {
            return Optional.of(Flow.ROCKS);
        }
        return Optional.empty();
    }

    /**
     * The Maven classifiers that might carry a {@link Flow}'s artifact, in priority order —
     * more than one real convention is in active use. Grounded in a direct inventory of
     * {@code ike-restricted} (Nexus REST Search API, {@code q=reasoned-sa}/{@code q=reasoned-pb}
     * against {@code https://nexus.tinkar.org}, 2026-07-21): {@code reasoned-sa}/
     * {@code unreasoned-sa} and {@code reasoned-pb}/{@code unreasoned-pb} (a fully processed,
     * classified variant and a raw one) is the dominant, current convention, confirmed across
     * ~15 distinct real artifact groups (including {@code dex-data}, {@code esh-data},
     * {@code komet-starter-data}, {@code snomedct-international}, {@code snomedct-us},
     * {@code loinc}, {@code rxnorm}, {@code gudid}, {@code vaextension},
     * {@code tinkar-example-data}, {@code tinkar-starter-data}). {@code spined-array} (bare,
     * observed only for {@code tinkar-snomedct-starter-data}) and the older, more general
     * {@code sa}/{@code pb} suffixes are real but rarer — kept as fallbacks, not primaries.
     * Callers should try each candidate in order and use whichever one actually resolves,
     * rather than assuming a single fixed classifier per flow.
     *
     * <p>{@link Flow#ROCKS} has no real published convention yet — unlike PB/SA, this wasn't
     * found in the Nexus inventory (only the unrelated {@code rocksdbjni} library jars matched
     * a search for "rocks"). {@code rkb} is a placeholder pending a real one.
     *
     * {@code changeset} is real too (confirmed against {@code network.ike.foundation:ike-changeset},
     * the chronology-builder project's own artifact — literally named for it, publishing exactly
     * one classifier for its payload zip, {@code changeset}, 2026-07-22) and semantically
     * identical to what {@link Flow#PB} means (a protobuf changeset zip placed as-is under
     * {@code ~/Solor}) — kept last since it's so far only observed for that one artifact family,
     * not yet the widespread convention {@code reasoned-pb}/{@code unreasoned-pb} are.
     *
     * @param flow the flow
     * @return the candidate classifiers, most-likely-correct first
     */
    public static List<String> classifierCandidates(Flow flow) {
        return switch (flow) {
            case PB -> List.of("reasoned-pb", "unreasoned-pb", "pb", "changeset");
            case SA -> List.of("reasoned-sa", "unreasoned-sa", "spined-array", "sa");
            case ROCKS -> List.of("rkb");
        };
    }

    /**
     * Picks which of a specific artifact's {@code availableClassifiers} — real, authoritative
     * data (a Nexus asset listing or a local {@code ~/.m2} directory listing), not a guess — to
     * use for {@code flow}, preferring {@link #classifierCandidates}'s earlier entries. An
     * artifact whose real classifiers don't intersect {@link #classifierCandidates} at all
     * simply has no variant compatible with this flow — callers should treat that as "not a
     * usable option" for this flow, not surface a way to force a mismatched classifier through.
     *
     * @param flow the flow
     * @param availableClassifiers the classifiers actually published/present for one specific
     *         artifact version
     * @return the best-matching classifier to use, or empty if none of {@code availableClassifiers}
     *         is one of {@link #classifierCandidates}
     */
    public static Optional<String> pickBestClassifier(Flow flow, Set<String> availableClassifiers) {
        Objects.requireNonNull(availableClassifiers, "availableClassifiers");
        return classifierCandidates(flow).stream()
                .filter(availableClassifiers::contains)
                .findFirst();
    }
}
