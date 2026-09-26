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

import javafx.geometry.Rectangle2D;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The windows that were open when Komet quit, recorded so the next launch reopens them where they
 * were, back to front (IKE-Network/ike-issues#1151). Each entry is stored as one string in the
 * knowledge base's preferences.
 */
final class OpenWindows {

    private static final String SEPARATOR = "|";

    private OpenWindows() {
    }

    /** The kinds of window that are reopened. */
    enum Kind {
        /** A journal, reopened from its own preferences, which already hold its place. */
        JOURNAL,
        /** A KL editor window on a saved layout, reopened at its recorded bounds. */
        KL_EDITOR
    }

    /**
     * A window to reopen.
     *
     * @param kind     what kind of window it is
     * @param key      the journal's topic, or the KL editor layout's title
     * @param standard for a KL editor window, whether the layout is a standard window
     * @param bounds   for a KL editor window, its bounds; empty for a journal
     */
    record Entry(Kind kind, String key, boolean standard, Optional<Rectangle2D> bounds) {

        /**
         * A journal entry.
         *
         * @param journalTopic the journal's topic
         * @return the entry
         */
        static Entry journal(UUID journalTopic) {
            return new Entry(Kind.JOURNAL, journalTopic.toString(), false, Optional.empty());
        }

        /**
         * A KL editor entry.
         *
         * @param layoutTitle the saved layout's title
         * @param standard    whether the layout is a standard window
         * @param bounds      the window's bounds
         * @return the entry
         */
        static Entry klEditor(String layoutTitle, boolean standard, Rectangle2D bounds) {
            return new Entry(Kind.KL_EDITOR, layoutTitle, standard, Optional.of(bounds));
        }
    }

    /**
     * Encodes an entry as its stored string. A KL editor's title goes last, so it may contain the
     * separator.
     *
     * @param entry the entry
     * @return the stored form
     */
    static String encode(Entry entry) {
        return switch (entry.kind()) {
            case JOURNAL -> Kind.JOURNAL.name() + SEPARATOR + entry.key();
            case KL_EDITOR -> {
                Rectangle2D bounds = entry.bounds().orElse(Rectangle2D.EMPTY);
                yield String.join(SEPARATOR, Kind.KL_EDITOR.name(), Boolean.toString(entry.standard()),
                        Double.toString(bounds.getMinX()), Double.toString(bounds.getMinY()),
                        Double.toString(bounds.getWidth()), Double.toString(bounds.getHeight()), entry.key());
            }
        };
    }

    /**
     * Decodes a stored string. A string that does not decode, for example one written by a
     * newer Komet, is empty rather than an error, so the rest of the list still reopens.
     *
     * @param stored the stored form
     * @return the entry, or empty when it does not decode
     */
    static Optional<Entry> decode(String stored) {
        try {
            String[] parts = stored.split("\\|", 7);
            Kind kind = Kind.valueOf(parts[0]);
            return switch (kind) {
                case JOURNAL -> parts.length == 2
                        ? Optional.of(Entry.journal(UUID.fromString(parts[1])))
                        : Optional.empty();
                case KL_EDITOR -> parts.length == 7 && !parts[6].isEmpty()
                        ? Optional.of(Entry.klEditor(parts[6], Boolean.parseBoolean(parts[1]),
                                new Rectangle2D(Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
                                        Double.parseDouble(parts[4]), Double.parseDouble(parts[5]))))
                        : Optional.empty();
            };
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * Decodes a stored list, skipping entries that do not decode.
     *
     * @param stored the stored strings, back to front
     * @return the entries, back to front
     */
    static List<Entry> decodeAll(List<String> stored) {
        return stored.stream().map(OpenWindows::decode).flatMap(Optional::stream).toList();
    }

    /**
     * Encodes a list of entries.
     *
     * @param entries the entries, back to front
     * @return the stored strings, back to front
     */
    static List<String> encodeAll(List<Entry> entries) {
        return entries.stream().map(OpenWindows::encode).toList();
    }

    /**
     * Encodes window bounds, for the landing page's saved place.
     *
     * @param bounds the bounds
     * @return the stored form
     */
    static String encodeBounds(Rectangle2D bounds) {
        return String.join(SEPARATOR, Double.toString(bounds.getMinX()), Double.toString(bounds.getMinY()),
                Double.toString(bounds.getWidth()), Double.toString(bounds.getHeight()));
    }

    /**
     * Decodes window bounds.
     *
     * @param stored the stored form
     * @return the bounds, or empty when they do not decode or have no area
     */
    static Optional<Rectangle2D> decodeBounds(String stored) {
        try {
            String[] parts = stored.split("\\|");
            if (parts.length != 4) {
                return Optional.empty();
            }
            Rectangle2D bounds = new Rectangle2D(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]), Double.parseDouble(parts[3]));
            return bounds.getWidth() > 0 && bounds.getHeight() > 0 ? Optional.of(bounds) : Optional.empty();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }
}
