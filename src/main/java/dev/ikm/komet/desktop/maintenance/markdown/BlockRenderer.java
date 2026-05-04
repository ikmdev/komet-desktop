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
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import jfx.incubator.scene.control.richtext.model.RichParagraph;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
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
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks a commonmark-java AST and emits {@link RichParagraph} instances onto a
 * caller-supplied list. One method per block type, one inline visitor that
 * composes {@link StyleAttributeMap} attributes for nested inline elements.
 *
 * <p>Block-level styling is delegated to CSS via the {@code addWithStyleNames}
 * APIs — paragraphs declare classes like {@code .h1}, {@code .blockquote},
 * {@code .code-block}, and the stylesheet supplies sizing, color, and padding.
 * Inline styling (bold, italic, strikethrough, monospace) travels in the
 * attribute map, which serializes cleanly to RTF / HTML on copy-out.
 *
 * <p>Pandoc-style attribute markers ({@code {.class #id key=val}}) at the
 * block or inline level are surfaced as {@link AttributedBlock} and
 * {@link AttributedInline} nodes by the {@code AttributesExtension}; the
 * renderer composes their classes onto the active CSS-class list and
 * recurses into the wrapped content.
 */
public class BlockRenderer {

    private static final Logger LOG = LoggerFactory.getLogger(BlockRenderer.class);

    private static final StyleAttributeMap CODE_INLINE = StyleAttributeMap.builder()
            .setFontFamily("Monospaced")
            .build();

    /**
     * Render one top-level block, appending one or more paragraphs to
     * {@code paragraphs} and the corresponding plain-text strings to
     * {@code plainTexts}. Lists, block quotes, attributed blocks, and tables
     * may contribute multiple paragraphs.
     */
    public void renderBlock(Node block, List<RichParagraph> paragraphs, List<String> plainTexts) {
        renderBlock(block, paragraphs, plainTexts, List.of());
    }

    private void renderBlock(Node block, List<RichParagraph> paragraphs, List<String> plainTexts,
                             List<String> styleClasses) {
        switch (block) {
            case AttributedBlock ab -> {
                Node wrapped = ab.getFirstChild();
                if (wrapped == null) {
                    // Unbound marker — no following block to attach to. Drop it.
                    return;
                }
                renderBlock(wrapped, paragraphs, plainTexts,
                        merge(styleClasses, ab.getAttributes().classes()));
            }
            case Heading h -> emit(renderHeading(h, styleClasses), plainTextOfInlines(h), paragraphs, plainTexts);
            case Paragraph p -> emit(renderParagraph(p, styleClasses), plainTextOfInlines(p), paragraphs, plainTexts);
            case BlockQuote q -> renderBlockQuote(q, paragraphs, plainTexts, styleClasses);
            case FencedCodeBlock fc -> emit(renderFencedCode(fc, styleClasses), nullToEmpty(fc.getLiteral()), paragraphs, plainTexts);
            case IndentedCodeBlock ic -> emit(renderIndentedCode(ic, styleClasses), nullToEmpty(ic.getLiteral()), paragraphs, plainTexts);
            case BulletList bl -> renderBulletList(bl, paragraphs, plainTexts, styleClasses);
            case OrderedList ol -> renderOrderedList(ol, paragraphs, plainTexts, styleClasses);
            case ThematicBreak _ -> emit(renderThematicBreak(), "---", paragraphs, plainTexts);
            case TableBlock tb -> emit(renderTable(tb), tableToPlainText(tb), paragraphs, plainTexts);
            default -> {
                String text = plainTextOfInlines(block);
                if (!text.isBlank()) {
                    RichParagraph.Builder b = RichParagraph.builder();
                    addText(b, text, StyleAttributeMap.EMPTY, styleClasses);
                    emit(b.build(), text, paragraphs, plainTexts);
                }
                LOG.debug("Unhandled block type {}; rendered as plain text", block.getClass().getSimpleName());
            }
        }
    }

    private static void emit(RichParagraph paragraph, String plain,
                             List<RichParagraph> paragraphs, List<String> plainTexts) {
        paragraphs.add(paragraph);
        plainTexts.add(plain);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Combine a parent's style classes with extra ones from an attribute marker.
     * Order is preserved — parent classes first, then the new ones — so a
     * stylesheet can rely on left-to-right precedence.
     */
    private static List<String> merge(List<String> parent, Set<String> extra) {
        if (extra == null || extra.isEmpty()) return parent;
        Set<String> combined = new LinkedHashSet<>(parent);
        combined.addAll(extra);
        return List.copyOf(combined);
    }

    private static List<String> with(List<String> parent, String extra) {
        if (extra == null) return parent;
        List<String> combined = new ArrayList<>(parent.size() + 1);
        combined.addAll(parent);
        if (!combined.contains(extra)) combined.add(extra);
        return List.copyOf(combined);
    }

    // -- Block renderers ----------------------------------------------------

    protected RichParagraph renderHeading(Heading h, List<String> styleClasses) {
        RichParagraph.Builder b = RichParagraph.builder();
        appendInlines(b, h.getFirstChild(), StyleAttributeMap.EMPTY,
                with(styleClasses, "h" + h.getLevel()));
        return b.build();
    }

    protected RichParagraph renderParagraph(Paragraph p, List<String> styleClasses) {
        RichParagraph.Builder b = RichParagraph.builder();
        appendInlines(b, p.getFirstChild(), StyleAttributeMap.EMPTY, styleClasses);
        return b.build();
    }

    protected void renderBlockQuote(BlockQuote q, List<RichParagraph> paragraphs, List<String> plainTexts,
                                    List<String> styleClasses) {
        List<String> quoteClasses = with(styleClasses, "blockquote");
        for (Node child = q.getFirstChild(); child != null; child = child.getNext()) {
            renderBlock(child, paragraphs, plainTexts, quoteClasses);
        }
    }

    protected RichParagraph renderFencedCode(FencedCodeBlock fc, List<String> styleClasses) {
        RichParagraph.Builder b = RichParagraph.builder();
        addText(b, nullToEmpty(fc.getLiteral()), StyleAttributeMap.EMPTY,
                with(styleClasses, "code-block"));
        return b.build();
    }

    protected RichParagraph renderIndentedCode(IndentedCodeBlock ic, List<String> styleClasses) {
        RichParagraph.Builder b = RichParagraph.builder();
        addText(b, nullToEmpty(ic.getLiteral()), StyleAttributeMap.EMPTY,
                with(styleClasses, "code-block"));
        return b.build();
    }

    protected void renderBulletList(BulletList list, List<RichParagraph> paragraphs, List<String> plainTexts,
                                    List<String> styleClasses) {
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (item instanceof ListItem li) {
                renderListItem(li, "•  ", paragraphs, plainTexts, styleClasses);
            } else {
                renderBlock(item, paragraphs, plainTexts, styleClasses);
            }
        }
    }

    protected void renderOrderedList(OrderedList list, List<RichParagraph> paragraphs, List<String> plainTexts,
                                     List<String> styleClasses) {
        int n = 1;
        for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (item instanceof ListItem li) {
                renderListItem(li, n + ".  ", paragraphs, plainTexts, styleClasses);
                n++;
            } else {
                renderBlock(item, paragraphs, plainTexts, styleClasses);
            }
        }
    }

    /**
     * Render one list item as one paragraph: {@code prefix} (bullet or
     * "{n}.") followed by the inline content of the item's first paragraph.
     * Subsequent paragraphs in the same item become continuation paragraphs
     * with no prefix.
     */
    protected void renderListItem(ListItem item, String prefix,
                                  List<RichParagraph> paragraphs, List<String> plainTexts,
                                  List<String> styleClasses) {
        boolean first = true;
        for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Paragraph p) {
                RichParagraph.Builder b = RichParagraph.builder();
                if (first) {
                    addText(b, prefix, StyleAttributeMap.EMPTY, styleClasses);
                }
                appendInlines(b, p.getFirstChild(), StyleAttributeMap.EMPTY, styleClasses);
                String plain = (first ? prefix : "") + plainTextOfInlines(p);
                emit(b.build(), plain, paragraphs, plainTexts);
                first = false;
            } else {
                // Nested list, code block, etc. — flush as its own paragraph(s).
                renderBlock(child, paragraphs, plainTexts, styleClasses);
            }
        }
    }

    protected RichParagraph renderThematicBreak() {
        RichParagraph.Builder b = RichParagraph.builder();
        b.addWithStyleNames("─────────────────────────", "thematic-break");
        return b.build();
    }

    /**
     * Render a Markdown table as one paragraph holding a single inline
     * {@code GridPane}. The supplier defers GridPane construction until the
     * paragraph scrolls into view.
     */
    protected RichParagraph renderTable(TableBlock tb) {
        RichParagraph.Builder b = RichParagraph.builder();
        b.addInlineNode(() -> buildTableNode(tb));
        return b.build();
    }

    private GridPane buildTableNode(TableBlock tb) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("md-table");
        grid.setHgap(0);
        grid.setVgap(0);

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

    private int columnsInFirstRow(Node section) {
        Node firstRow = section.getFirstChild();
        int n = 0;
        if (firstRow instanceof TableRow tr) {
            for (Node c = tr.getFirstChild(); c != null; c = c.getNext()) n++;
        }
        return n;
    }

    private int renderTableSection(Node section, GridPane grid, int startRow, boolean header) {
        int row = startRow;
        for (Node rowNode = section.getFirstChild(); rowNode != null; rowNode = rowNode.getNext()) {
            if (!(rowNode instanceof TableRow tr)) continue;
            int col = 0;
            for (Node cellNode = tr.getFirstChild(); cellNode != null; cellNode = cellNode.getNext()) {
                if (!(cellNode instanceof TableCell tc)) continue;
                Label cell = buildTableCell(tc);
                cell.getStyleClass().add(header ? "md-table-header" : "md-table-cell");
                grid.add(cell, col, row);
                col++;
            }
            row++;
        }
        return row;
    }

    /**
     * Build the visual node for a table cell, honoring inline {@code {.class}}
     * markers on its first child (the common case for state badges like
     * {@code `Active`{.state-active}}). For richer cell content we fall back
     * to a plain {@link Label}; styled inline runs would need a richer cell
     * implementation than {@link Label} provides.
     */
    private Label buildTableCell(TableCell tc) {
        Label cell = new Label(plainTextOfInlines(tc));
        cell.setMaxWidth(Double.MAX_VALUE);
        cell.setPadding(new Insets(2, 8, 2, 8));
        cell.setAlignment(Pos.CENTER_LEFT);
        // If the cell is exactly one AttributedInline (e.g., `Active`{.state-active}),
        // promote its classes to the cell label so colored badges render in tables.
        Node only = singleAttributedInline(tc);
        if (only instanceof AttributedInline ai) {
            for (String c : ai.getAttributes().classes()) {
                cell.getStyleClass().add(c);
            }
        }
        return cell;
    }

    private static Node singleAttributedInline(Node container) {
        Node first = container.getFirstChild();
        if (first == null || first.getNext() != null) return null;
        return first;
    }

    private static String tableToPlainText(TableBlock tb) {
        StringBuilder sb = new StringBuilder();
        for (Node section = tb.getFirstChild(); section != null; section = section.getNext()) {
            for (Node rowNode = section.getFirstChild(); rowNode != null; rowNode = rowNode.getNext()) {
                for (Node cellNode = rowNode.getFirstChild(); cellNode != null; cellNode = cellNode.getNext()) {
                    sb.append(plainTextOfInlines(cellNode)).append('\t');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    // -- Inline visitor -----------------------------------------------------

    /**
     * Walk the inline children of {@code first} and append them to the
     * paragraph builder. {@code base} carries the inherited inline style;
     * nested elements compose by merging additional attributes.
     * {@code styleClasses} is the active CSS-class list applied to text
     * segments via {@code addWithStyleNames}; {@link AttributedInline} wrappers
     * extend the list for their children.
     */
    protected void appendInlines(RichParagraph.Builder b, Node first, StyleAttributeMap base,
                                 List<String> styleClasses) {
        Node n = first;
        while (n != null) {
            switch (n) {
                case Text t -> addText(b, t.getLiteral(), base, styleClasses);
                case StrongEmphasis s ->
                        appendInlines(b, s.getFirstChild(),
                                merge(base, StyleAttributeMap.builder().setBold(true).build()), styleClasses);
                case Emphasis e ->
                        appendInlines(b, e.getFirstChild(),
                                merge(base, StyleAttributeMap.builder().setItalic(true).build()), styleClasses);
                case Strikethrough sk ->
                        appendInlines(b, sk.getFirstChild(),
                                merge(base, StyleAttributeMap.builder().setStrikeThrough(true).build()), styleClasses);
                case Code c -> emitCode(b, c.getLiteral(), base, styleClasses);
                case AttributedInline ai ->
                        appendInlines(b, ai.getFirstChild(), base,
                                merge(styleClasses, ai.getAttributes().classes()));
                case Link l -> b.addInlineNode(() -> linkNode(plainTextOfInlines(l), l.getDestination()));
                case Image img -> addText(b, "[image: " + img.getDestination() + "]", base, styleClasses);
                case SoftLineBreak _ -> addText(b, " ", base, styleClasses);
                case HardLineBreak _ -> addText(b, "\n", base, styleClasses);
                default -> addText(b, plainTextOfInlines(n), base, styleClasses);
            }
            n = n.getNext();
        }
    }

    private static void emitCode(RichParagraph.Builder b, String literal, StyleAttributeMap base,
                                 List<String> styleClasses) {
        if (styleClasses.isEmpty()) {
            b.addSegment(literal, merge(base, CODE_INLINE));
        } else {
            // Inline code with surrounding attribute classes — the CSS class
            // list takes over visual styling, so the monospaced font has to
            // come from CSS too (see .inline-code in markdown.css).
            List<String> withCode = with(styleClasses, "inline-code");
            b.addWithStyleNames(literal, withCode.toArray(new String[0]));
        }
    }

    private static StyleAttributeMap merge(StyleAttributeMap a, StyleAttributeMap b) {
        if (a == null || a.isEmpty()) return b;
        if (b == null || b.isEmpty()) return a;
        return a.combine(b);
    }

    private static void addText(RichParagraph.Builder b, String text, StyleAttributeMap base,
                                List<String> styleClasses) {
        if (text == null || text.isEmpty()) return;
        if (styleClasses.isEmpty() && base.isEmpty()) {
            b.addSegment(text);
        } else if (!styleClasses.isEmpty()) {
            // Style-names path: the active CSS-class list drives visuals. Inline
            // attributes (bold/italic/strike) inside a styled block are dropped
            // here — that's a deliberate simplification matching the read-only
            // viewer use case.
            b.addWithStyleNames(text, styleClasses.toArray(new String[0]));
        } else {
            b.addSegment(text, base);
        }
    }

    /** Concatenate the {@code Text}/{@code Code}/etc. literals under {@code n} for plain-text export. */
    private static String plainTextOfInlines(Node n) {
        StringBuilder sb = new StringBuilder();
        appendPlainText(n, sb);
        return sb.toString();
    }

    private static void appendPlainText(Node n, StringBuilder sb) {
        for (Node c = n.getFirstChild(); c != null; c = c.getNext()) {
            switch (c) {
                case Text t -> sb.append(t.getLiteral());
                case Code code -> sb.append(code.getLiteral());
                case SoftLineBreak _ -> sb.append(' ');
                case HardLineBreak _ -> sb.append('\n');
                default -> appendPlainText(c, sb);
            }
        }
    }

    private static Hyperlink linkNode(String text, String url) {
        Hyperlink link = new Hyperlink(text);
        link.setOnAction(_ -> openInBrowser(url));
        return link;
    }

    private static void openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            } else {
                LOG.info("No desktop browser support; ignoring link click for {}", url);
            }
        } catch (Exception e) {
            LOG.warn("Failed to open URL {}", url, e);
        }
    }

    @SuppressWarnings("unused") // kept for source-symmetry with earlier per-class API
    private static List<String> singletonOrEmpty(String s) {
        return s == null ? List.of() : Collections.singletonList(s);
    }
}
