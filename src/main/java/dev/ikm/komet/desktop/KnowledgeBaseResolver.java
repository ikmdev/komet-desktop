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

import dev.ikm.tinkar.common.service.DataServiceController;
import dev.ikm.tinkar.common.service.DataUriOption;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Resolves a {@code --kb} knowledge base to the provider that opens it and the picker entry for it
 * (IKE-Network/ike-issues#1139). A knowledge base resolves only if an "Open …" provider would list
 * it in the picker, so the command line opens exactly what the picker can open, by the same
 * provider.
 */
final class KnowledgeBaseResolver {

    /** Only the "Open …" providers open an existing knowledge base; "New …" and "Load …" create one. */
    static final String OPEN_PROVIDER_PREFIX = "Open ";

    private KnowledgeBaseResolver() {
    }

    /**
     * One provider and the knowledge bases it would list in the picker.
     *
     * @param controllerName the provider's {@code controllerName()}
     * @param options        the knowledge bases it lists
     */
    record Candidate(String controllerName, List<DataUriOption> options) {
    }

    /** The outcome of resolving a {@code --kb} knowledge base. */
    sealed interface Resolution {
    }

    /**
     * Exactly one provider opens the knowledge base.
     *
     * @param controllerName the provider that opens it
     * @param option         its picker entry
     */
    record Found(String controllerName, DataUriOption option) implements Resolution {
    }

    /**
     * The knowledge base cannot be opened as given.
     *
     * @param message why, written for the person who launched Komet
     */
    record Unresolved(String message) implements Resolution {
    }

    /**
     * The candidates the running providers offer: every "Open …" data provider with its picker
     * entries.
     *
     * @param controllers the discovered data-provider controllers
     * @return one candidate per "Open …" provider
     */
    static List<Candidate> candidates(List<? extends DataServiceController<?>> controllers) {
        List<Candidate> candidates = new ArrayList<>();
        for (DataServiceController<?> controller : controllers) {
            if (controller.controllerName().startsWith(OPEN_PROVIDER_PREFIX)) {
                candidates.add(new Candidate(controller.controllerName(), controller.providerOptions()));
            }
        }
        return candidates;
    }

    /**
     * Resolves {@code knowledgeBase} against the candidates. An absolute path, or one starting with
     * {@code ~/}, is taken as given; a relative one is the name of a folder under
     * {@code solorFolder}, as the picker lists them.
     *
     * @param knowledgeBase the {@code --kb} value
     * @param solorFolder   the folder the picker lists knowledge bases from
     * @param homeFolder    the user's home folder, for a {@code ~/} path
     * @param candidates    the "Open …" providers and their picker entries
     * @return the provider and entry, or why there is none
     */
    static Resolution resolve(String knowledgeBase, Path solorFolder, Path homeFolder, List<Candidate> candidates) {
        Path folder = folderFor(knowledgeBase, solorFolder, homeFolder);
        if (!Files.isDirectory(folder)) {
            return new Unresolved("No knowledge base folder at " + folder + ".");
        }
        List<Found> matches = new ArrayList<>();
        for (Candidate candidate : candidates) {
            for (DataUriOption option : candidate.options()) {
                if (sameFolder(option, folder)) {
                    matches.add(new Found(candidate.controllerName(), option));
                }
            }
        }
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.isEmpty()) {
            return new Unresolved(folder + " is not a knowledge base any provider opens (Rocks, SpinedArray). "
                    + "The picker lists knowledge bases under " + solorFolder + ".");
        }
        return new Unresolved(folder + " can be opened by more than one provider ("
                + matches.stream().map(Found::controllerName).collect(Collectors.joining(", "))
                + "); choose one in the picker.");
    }

    private static Path folderFor(String knowledgeBase, Path solorFolder, Path homeFolder) {
        if (knowledgeBase.startsWith("~/")) {
            return homeFolder.resolve(knowledgeBase.substring(2)).normalize();
        }
        Path path = Path.of(knowledgeBase);
        return (path.isAbsolute() ? path : solorFolder.resolve(path)).normalize();
    }

    private static boolean sameFolder(DataUriOption option, Path folder) {
        if (!"file".equalsIgnoreCase(option.uri().getScheme())) {
            return false;
        }
        return Path.of(option.uri()).normalize().equals(folder);
    }
}
