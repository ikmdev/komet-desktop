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
package dev.ikm.komet.desktop.alerts;

import dev.ikm.tinkar.common.alert.AlertLog;
import dev.ikm.tinkar.common.alert.AlertType;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The words of the Warnings and Errors list: the {@link AlertLog}'s entries as one Markdown
 * document, newest first, and the one-line count the landing page shows.
 */
public final class WarningsAndErrors {

    /** The most entries the list shows; the knowledge base's file keeps the rest. */
    static final int SHOWN = 200;

    /** The most lines of a stack trace the list shows. */
    static final int TRACE_LINES = 15;

    private static final DateTimeFormatter DAY_AND_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private WarningsAndErrors() {
    }

    /**
     * The list as Markdown for the rich text view: what was reported since Komet started, then
     * what earlier runs reported, each newest first.
     *
     * @param entries the log's entries, oldest first
     * @param zone    the time zone to give times in
     * @return the Markdown
     */
    public static String markdown(List<AlertLog.Entry> entries, ZoneId zone) {
        return text(entries, zone, true);
    }

    /**
     * The list as plain text, for the clipboard.
     *
     * @param entries the log's entries, oldest first
     * @param zone    the time zone to give times in
     * @return the text
     */
    public static String plainText(List<AlertLog.Entry> entries, ZoneId zone) {
        return text(entries, zone, false);
    }

    /**
     * What the landing page says beside "Warnings and Errors".
     *
     * @param entries the log's entries
     * @param unseen  how many were reported since a person last looked
     * @return how many of each kind were reported, a repeat counted each time, and how many
     * of them are new; or "None"
     */
    public static String summary(List<AlertLog.Entry> entries, int unseen) {
        if (entries.isEmpty()) {
            return "None";
        }
        int errors = 0;
        int warnings = 0;
        int notes = 0;
        for (AlertLog.Entry entry : entries) {
            switch (entry.type()) {
                case ERROR -> errors += entry.count();
                case WARNING -> warnings += entry.count();
                default -> notes += entry.count();
            }
        }
        StringBuilder text = new StringBuilder();
        append(text, errors, "error");
        append(text, warnings, "warning");
        append(text, notes, "note");
        if (unseen > 0) {
            text.append(" (").append(unseen).append(" new)");
        }
        return text.toString();
    }

    private static void append(StringBuilder text, int count, String noun) {
        if (count == 0) {
            return;
        }
        if (!text.isEmpty()) {
            text.append(", ");
        }
        text.append(count).append(' ').append(noun).append(count == 1 ? "" : "s");
    }

    private static String text(List<AlertLog.Entry> entries, ZoneId zone, boolean markdown) {
        if (entries.isEmpty()) {
            return "No warnings or errors.\n\nWhat Komet reports while it starts, imports and works on this"
                    + " knowledge base is listed here. You can read it at any time.\n";
        }
        StringBuilder out = new StringBuilder();
        int from = Math.max(0, entries.size() - SHOWN);
        Boolean earlier = null;
        for (int i = entries.size() - 1; i >= from; i--) {
            AlertLog.Entry entry = entries.get(i);
            if (markdown && earlier != null) {
                // An empty line between entries, which the view otherwise sets one against the next.
                out.append("\u00A0\n\n");
            }
            if (earlier == null || earlier != entry.earlierRun()) {
                earlier = entry.earlierRun();
                heading(out, 2, earlier ? "Before Komet started" : "Since Komet started", markdown);
            }
            entry(out, entry, zone, markdown);
        }
        if (from > 0) {
            out.append(from).append(from == 1 ? " older entry is" : " older entries are")
                    .append(" not shown. The file ").append(markdown ? "**" : "").append(AlertLog.FILE_NAME)
                    .append(markdown ? "**" : "").append(" in the knowledge base's folder has them.\n");
        }
        return out.toString();
    }

    private static void entry(StringBuilder out, AlertLog.Entry entry, ZoneId zone, boolean markdown) {
        heading(out, 3, kind(entry.type()) + ": " + entry.title(), markdown);
        out.append(DAY_AND_TIME.format(entry.first().atZone(zone)));
        if (entry.count() > 1) {
            out.append(", then ").append(entry.count() - 1).append(entry.count() == 2 ? " more time" : " more times")
                    .append(", the last at ").append(sameDay(entry, zone)
                            ? TIME.format(entry.last().atZone(zone)) : DAY_AND_TIME.format(entry.last().atZone(zone)));
        }
        out.append("\n\n");
        // A line of its own for each line of the description, which a Markdown paragraph would run together.
        for (String line : entry.description().split("\n")) {
            if (!line.isBlank()) {
                out.append(markdown ? escape(line.strip()) : line.strip()).append("\n\n");
            }
        }
        if (!entry.detail().isEmpty()) {
            String[] lines = entry.detail().split("\n");
            if (markdown) {
                out.append("~~~~\n");
            }
            for (int i = 0; i < Math.min(lines.length, TRACE_LINES); i++) {
                out.append(lines[i].replace("\t", "    ")).append('\n');
            }
            if (lines.length > TRACE_LINES) {
                out.append("… ").append(lines.length - TRACE_LINES).append(" more lines, in Komet's log\n");
            }
            out.append(markdown ? "~~~~\n\n" : "\n");
        }
    }

    private static boolean sameDay(AlertLog.Entry entry, ZoneId zone) {
        return entry.first().atZone(zone).toLocalDate().equals(entry.last().atZone(zone).toLocalDate());
    }

    private static void heading(StringBuilder out, int level, String text, boolean markdown) {
        if (markdown) {
            out.append("#".repeat(level)).append(' ').append(escape(text)).append("\n\n");
        } else {
            out.append(text).append("\n\n");
        }
    }

    private static String kind(AlertType type) {
        return switch (type) {
            case ERROR -> "Error";
            case WARNING -> "Warning";
            default -> "Note";
        };
    }

    /**
     * A line of an alert as Markdown that draws as the same characters: an alert's brackets,
     * underscores and angle brackets are its own, not markup.
     */
    static String escape(String line) {
        StringBuilder out = new StringBuilder(line.length() + 16);
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            boolean lineStart = i == 0;
            boolean markup = "\\`*_[]<>#|~&{}!".indexOf(c) >= 0
                    || (lineStart && "-+=>".indexOf(c) >= 0)
                    || ((c == '.' || c == ')') && i > 0 && digitsBefore(line, i));
            if (markup) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /** @return true if everything before {@code at} is digits, which would make the line a numbered list item */
    private static boolean digitsBefore(String line, int at) {
        for (int i = 0; i < at; i++) {
            if (!Character.isDigit(line.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
