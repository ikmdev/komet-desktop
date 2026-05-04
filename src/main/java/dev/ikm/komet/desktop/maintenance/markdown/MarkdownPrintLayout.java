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
package dev.ikm.komet.desktop.maintenance.markdown;

import dev.ikm.markdown.attributes.AttributedBlock;
import dev.ikm.markdown.attributes.AttributedInline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds a fully-rendered, non-virtualized JavaFX node tree from Markdown
 * source — suitable for printing. The {@link dev.ikm.komet.desktop.maintenance.ChangeSetSummaryWindow}
 * uses this in conjunction with {@link javafx.print.PrinterJob} to paginate
 * the entire document, in contrast to the screen renderer which virtualizes
 * paragraphs through a {@code RichTextArea}.
 *
 * <p>The output is a {@link VBox} with the {@code .markdown-view} CSS class,
 * so the same {@code markdown.css} that themes the on-screen viewer also
 * themes the printout. Each Markdown block becomes one or more child nodes:
 *
 * <ul>
 *   <li>Heading → {@link Label} tagged {@code .h1}…{@code .h6}</li>
 *   <li>Paragraph → {@link TextFlow} with per-run {@link Text} (bold / italic / strikethrough / code)
 *       and embedded inline {@link Label} nodes for state-badge style spans</li>
 *   <li>BlockQuote → nested {@link VBox} tagged {@code .blockquote}</li>
 *   <li>FencedCodeBlock / IndentedCodeBlock → {@link Label} tagged {@code .code-block}</li>
 *   <li>List → {@link VBox} of {@link HBox} (bullet/number prefix + content)</li>
 *   <li>Table → {@link GridPane} matching the on-screen layout</li>
 *   <li>AttributedBlock / AttributedInline → propagate Pandoc-style classes onto
 *       the wrapped node's {@code styleClass} list, exactly as the screen renderer does</li>
 * </ul>
 *
 * <p>Pagination is the caller's responsibility — see
 * {@link dev.ikm.komet.desktop.maintenance.ChangeSetSummaryWindow#printReport}.
 */
public final class MarkdownPrintLayout {

    private MarkdownPrintLayout() { /* static helper */ }

    /**
     * Parse {@code markdown} and return a fully-populated layout VBox.
     * Uses the same parser configuration as {@link MarkdownTextModel}, so
     * tables, strikethrough, and the IKE attribute extension all work.
     */
    public static VBox build(String markdown) {
        VBox root = new VBox();
        root.getStyleClass().add("markdown-view");
        root.setSpacing(2);
        root.setFillWidth(true);
        Parser parser = MarkdownTextModel.defaultParser();
        Node document = parser.parse(markdown);
        for (Node block = document.getFirstChild(); block != null; block = block.getNext()) {
            renderBlock(block, root, List.of());
        }
        return root;
    }

    // -- Block rendering ----------------------------------------------------

    private static void renderBlock(Node block, VBox parent, List<String> styleClasses) {
        switch (block) {
            case AttributedBlock ab -> {
                Node wrapped = ab.getFirstChild();
                if (wrapped != null) {
                    renderBlock(wrapped, parent, merge(styleClasses, ab.getAttributes().classes()));
                }
            }
            case Heading h -> {
                Label label = new Label(plainTextOfInlines(h));
                label.setWrapText(true);
                label.setMaxWidth(Double.MAX_VALUE);
                applyClasses(label, with(styleClasses, "h" + h.getLevel()));
                parent.getChildren().add(label);
            }
            case Paragraph p -> {
                TextFlow flow = paragraphFlow(p);
                applyClasses(flow, styleClasses);
                parent.getChildren().add(flow);
            }
            case BlockQuote q -> {
                VBox inner = new VBox(2);
                inner.setPadding(new Insets(0, 0, 0, 12));
                applyClasses(inner, with(styleClasses, "blockquote"));
                for (Node child = q.getFirstChild(); child != null; child = child.getNext()) {
                    renderBlock(child, inner, List.of());
                }
                parent.getChildren().add(inner);
            }
            case FencedCodeBlock fc -> {
                Label label = new Label(nullToEmpty(fc.getLiteral()));
                label.setWrapText(true);
                label.setMaxWidth(Double.MAX_VALUE);
                applyClasses(label, with(styleClasses, "code-block"));
                parent.getChildren().add(label);
            }
            case IndentedCodeBlock ic -> {
                Label label = new Label(nullToEmpty(ic.getLiteral()));
                label.setWrapText(true);
                label.setMaxWidth(Double.MAX_VALUE);
                applyClasses(label, with(styleClasses, "code-block"));
                parent.getChildren().add(label);
            }
            case BulletList bl -> {
                for (Node item = bl.getFirstChild(); item != null; item = item.getNext()) {
                    if (item instanceof ListItem li) {
                        parent.getChildren().add(listItemRow("• ", li, styleClasses));
                    }
                }
            }
            case OrderedList ol -> {
                int n = 1;
                for (Node item = ol.getFirstChild(); item != null; item = item.getNext()) {
                    if (item instanceof ListItem li) {
                        parent.getChildren().add(listItemRow(n + ". ", li, styleClasses));
                        n++;
                    }
                }
            }
            case ThematicBreak _ -> parent.getChildren().add(new Separator());
            case TableBlock tb -> {
                GridPane grid = buildTable(tb);
                applyClasses(grid, styleClasses);
                parent.getChildren().add(grid);
            }
            default -> {
                String text = plainTextOfInlines(block);
                if (!text.isBlank()) {
                    Label label = new Label(text);
                    label.setWrapText(true);
                    applyClasses(label, styleClasses);
                    parent.getChildren().add(label);
                }
            }
        }
    }

    private static HBox listItemRow(String prefix, ListItem item, List<String> styleClasses) {
        HBox row = new HBox(4);
        Label bullet = new Label(prefix);
        bullet.setMinWidth(Region.USE_PREF_SIZE);
        VBox content = new VBox(2);
        HBox.setHgrow(content, Priority.ALWAYS);
        for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Paragraph p) {
                TextFlow flow = paragraphFlow(p);
                applyClasses(flow, styleClasses);
                content.getChildren().add(flow);
            } else {
                renderBlock(child, content, styleClasses);
            }
        }
        row.getChildren().addAll(bullet, content);
        applyClasses(row, styleClasses);
        return row;
    }

    // -- Inline rendering into a TextFlow -----------------------------------

    private static TextFlow paragraphFlow(Paragraph p) {
        TextFlow flow = new TextFlow();
        appendInlines(flow, p.getFirstChild(), InlineStyle.PLAIN, Set.of());
        return flow;
    }

    /**
     * Walk inline children and append {@link Text} runs (or embedded
     * {@link Label} nodes for badged code spans) to the flow. Inline style
     * is composed via {@link InlineStyle}; CSS classes accumulate so
     * Pandoc-style attribute markers wrap their child runs.
     */
    private static void appendInlines(TextFlow flow, Node first, InlineStyle style, Set<String> classes) {
        for (Node n = first; n != null; n = n.getNext()) {
            switch (n) {
                case org.commonmark.node.Text t -> appendTextRun(flow, t.getLiteral(), style, classes);
                case StrongEmphasis s -> appendInlines(flow, s.getFirstChild(), style.bold(true), classes);
                case Emphasis e -> appendInlines(flow, e.getFirstChild(), style.italic(true), classes);
                case Strikethrough sk -> appendInlines(flow, sk.getFirstChild(), style.strikethrough(true), classes);
                case Code c -> appendCodeSpan(flow, c.getLiteral(), classes);
                case Link l -> {
                    // Underline links so they're visible on paper.
                    InlineStyle linkStyle = style.underline(true);
                    appendInlines(flow, l.getFirstChild(), linkStyle, classes);
                }
                case org.commonmark.node.Image img -> appendTextRun(flow, "[image: " + img.getDestination() + "]", style, classes);
                case SoftLineBreak _ -> appendTextRun(flow, " ", style, classes);
                case HardLineBreak _ -> appendTextRun(flow, "\n", style, classes);
                case AttributedInline ai -> {
                    Set<String> combined = new LinkedHashSet<>(classes);
                    combined.addAll(ai.getAttributes().classes());
                    appendInlines(flow, ai.getFirstChild(), style, combined);
                }
                default -> appendTextRun(flow, plainTextOfInlines(n), style, classes);
            }
        }
    }

    private static void appendTextRun(TextFlow flow, String content, InlineStyle style, Set<String> classes) {
        if (content == null || content.isEmpty()) return;
        Text textNode = new Text(content);
        textNode.setFont(style.toFont(null));
        if (style.strikethrough()) textNode.setStrikethrough(true);
        if (style.underline()) textNode.setUnderline(true);
        for (String c : classes) textNode.getStyleClass().add(c);
        flow.getChildren().add(textNode);
    }

    /**
     * Inline code spans render as embedded {@link Label} nodes inside the
     * {@link TextFlow}. A Label can carry a CSS background, which a bare
     * {@link Text} run cannot — that's how the {@code .state-active} /
     * {@code .state-withdrawn} badges keep their colored backgrounds in
     * print, matching the on-screen view.
     */
    private static void appendCodeSpan(TextFlow flow, String content, Set<String> classes) {
        Label badge = new Label(content == null ? "" : content);
        badge.getStyleClass().add("inline-code");
        for (String c : classes) badge.getStyleClass().add(c);
        flow.getChildren().add(badge);
    }

    // -- Tables -------------------------------------------------------------

    private static GridPane buildTable(TableBlock tb) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("md-table");

        int row = 0;
        int columnCount = 0;
        for (Node section = tb.getFirstChild(); section != null; section = section.getNext()) {
            if (section instanceof TableHead head) {
                row = renderTableSection(head, grid, row, true);
                columnCount = Math.max(columnCount, columnsInFirstRow(head));
            } else if (section instanceof TableBody body) {
                row = renderTableSection(body, grid, row, false);
                columnCount = Math.max(columnCount, columnsInFirstRow(body));
            }
        }
        for (int i = 0; i < columnCount; i++) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setHgrow(Priority.SOMETIMES);
            grid.getColumnConstraints().add(cc);
        }
        return grid;
    }

    private static int columnsInFirstRow(Node section) {
        Node firstRow = section.getFirstChild();
        int n = 0;
        if (firstRow instanceof TableRow tr) {
            for (Node ignored = tr.getFirstChild(); ignored != null; ignored = ignored.getNext()) n++;
        }
        return n;
    }

    private static int renderTableSection(Node section, GridPane grid, int startRow, boolean header) {
        int row = startRow;
        for (Node rowNode = section.getFirstChild(); rowNode != null; rowNode = rowNode.getNext()) {
            if (!(rowNode instanceof TableRow tr)) continue;
            int col = 0;
            for (Node cellNode = tr.getFirstChild(); cellNode != null; cellNode = cellNode.getNext()) {
                if (!(cellNode instanceof TableCell tc)) continue;
                Node singleAttrInline = singleAttributedInlineChild(tc);
                Set<String> cellClasses = singleAttrInline == null
                        ? Set.of()
                        : new LinkedHashSet<>(((AttributedInline) singleAttrInline).getAttributes().classes());

                TextFlow flow = new TextFlow();
                Node inlinesStart = singleAttrInline == null
                        ? tc.getFirstChild()
                        : ((AttributedInline) singleAttrInline).getFirstChild();
                appendInlines(flow, inlinesStart, InlineStyle.PLAIN, cellClasses);
                flow.setMaxWidth(Double.MAX_VALUE);
                flow.setPadding(new Insets(2, 8, 2, 8));
                flow.getStyleClass().add(header ? "md-table-header" : "md-table-cell");
                for (String c : cellClasses) flow.getStyleClass().add(c);
                GridPane.setHgrow(flow, Priority.SOMETIMES);
                GridPane.setFillWidth(flow, true);
                grid.add(flow, col, row);
                col++;
            }
            row++;
        }
        return row;
    }

    /**
     * If a table cell's only child is an {@link AttributedInline}, return
     * that node; otherwise null. This lets the print renderer promote the
     * attribute classes (e.g., {@code .state-active}) onto the cell itself
     * so the badge background fills the cell, matching the on-screen
     * behavior in {@link BlockRenderer}.
     */
    private static Node singleAttributedInlineChild(Node cell) {
        Node first = cell.getFirstChild();
        if (first == null) return null;
        if (first.getNext() != null) return null;
        return first instanceof AttributedInline ? first : null;
    }

    // -- Helpers ------------------------------------------------------------

    private static String plainTextOfInlines(Node n) {
        StringBuilder sb = new StringBuilder();
        appendPlainText(n, sb);
        return sb.toString();
    }

    private static void appendPlainText(Node n, StringBuilder sb) {
        for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
            switch (c) {
                case org.commonmark.node.Text t -> sb.append(t.getLiteral());
                case Code code -> sb.append(code.getLiteral());
                case SoftLineBreak _ -> sb.append(' ');
                case HardLineBreak _ -> sb.append('\n');
                default -> appendPlainText(c, sb);
            }
        }
    }

    private static void applyClasses(javafx.scene.Node node, List<String> classes) {
        if (classes == null || classes.isEmpty()) return;
        for (String c : classes) {
            if (!node.getStyleClass().contains(c)) {
                node.getStyleClass().add(c);
            }
        }
    }

    private static List<String> with(List<String> parent, String extra) {
        if (extra == null) return parent;
        java.util.List<String> combined = new java.util.ArrayList<>(parent.size() + 1);
        combined.addAll(parent);
        if (!combined.contains(extra)) combined.add(extra);
        return List.copyOf(combined);
    }

    private static List<String> merge(List<String> parent, Set<String> extra) {
        if (extra == null || extra.isEmpty()) return parent;
        Set<String> combined = new LinkedHashSet<>(parent);
        combined.addAll(extra);
        return List.copyOf(combined);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Immutable inline-style record. Each level of inline nesting
     * (strong, emphasis, code, link, strikethrough) returns a new
     * {@code InlineStyle} via the with-style helpers.
     */
    private record InlineStyle(boolean bold, boolean italic, boolean strikethrough,
                               boolean underline, boolean monospace) {

        static final InlineStyle PLAIN = new InlineStyle(false, false, false, false, false);

        InlineStyle bold(boolean v)          { return new InlineStyle(v, italic, strikethrough, underline, monospace); }
        InlineStyle italic(boolean v)        { return new InlineStyle(bold, v, strikethrough, underline, monospace); }
        InlineStyle strikethrough(boolean v) { return new InlineStyle(bold, italic, v, underline, monospace); }
        InlineStyle underline(boolean v)     { return new InlineStyle(bold, italic, strikethrough, v, monospace); }

        Font toFont(Double size) {
            FontWeight weight = bold ? FontWeight.BOLD : FontWeight.NORMAL;
            FontPosture posture = italic ? FontPosture.ITALIC : FontPosture.REGULAR;
            String family = monospace ? "Monospaced" : null;
            double sz = size == null ? -1 : size;
            return Font.font(family, weight, posture, sz);
        }
    }
}
