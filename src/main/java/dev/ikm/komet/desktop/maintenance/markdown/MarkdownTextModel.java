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

import dev.ikm.markdown.attributes.AttributesExtension;
import jfx.incubator.scene.control.richtext.StyleResolver;
import jfx.incubator.scene.control.richtext.TextPos;
import jfx.incubator.scene.control.richtext.model.RichParagraph;
import jfx.incubator.scene.control.richtext.model.StyleAttributeMap;
import jfx.incubator.scene.control.richtext.model.StyledTextModelViewOnlyBase;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A view-only {@link jfx.incubator.scene.control.richtext.model.StyledTextModel}
 * that projects a Markdown document onto the {@code RichTextArea} paragraph
 * model. Top-level Markdown blocks are walked once at construction and
 * pre-rendered into {@link RichParagraph} instances by {@link BlockRenderer};
 * they're then served from {@link #getParagraph(int)} on demand.
 *
 * <p>Lists are flattened: each list item becomes one paragraph with a
 * left-side bullet/number prefix. Tables are rendered as a single inline
 * {@code GridPane} via {@code RichParagraph.Builder.addInlineNode}, since the
 * paragraph model is flat. Fenced code blocks use a monospaced font and the
 * {@code .code-block} CSS class.
 *
 * <p>The renderer is best-effort: unknown block or inline types fall back to
 * their plain-text representation. The model is read-only — no editing is
 * supported. For a writable Markdown editor, see the split-view pattern
 * recommended by the JavaFX 26 RichTextArea documentation.
 */
public final class MarkdownTextModel extends StyledTextModelViewOnlyBase {

    /**
     * Default commonmark parser configuration with GFM tables, strikethrough,
     * and the IKE Pandoc-style {@code {.class #id}} attribute extension.
     */
    public static Parser defaultParser() {
        return Parser.builder()
                .extensions(List.of(
                        TablesExtension.create(),
                        StrikethroughExtension.create(),
                        AttributesExtension.create()))
                .build();
    }

    /**
     * Convenience factory that parses {@code markdown} with the
     * {@link #defaultParser() default parser} and renders it with a stock
     * {@link BlockRenderer}.
     */
    public static MarkdownTextModel of(String markdown) {
        return new MarkdownTextModel(markdown, defaultParser(), new BlockRenderer());
    }

    private final List<RichParagraph> paragraphs;
    private final List<String> plainTexts;

    public MarkdownTextModel(String markdown, Parser parser, BlockRenderer renderer) {
        Objects.requireNonNull(markdown, "markdown");
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(renderer, "renderer");

        Node document = parser.parse(markdown);
        List<RichParagraph> ps = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (Node block = document.getFirstChild(); block != null; block = block.getNext()) {
            renderer.renderBlock(block, ps, texts);
        }
        if (ps.isEmpty()) {
            // A model must have at least one paragraph so RichTextArea has something to lay out.
            ps.add(RichParagraph.builder().build());
            texts.add("");
        }
        this.paragraphs = List.copyOf(ps);
        this.plainTexts = List.copyOf(texts);
    }

    @Override
    public int size() {
        return paragraphs.size();
    }

    @Override
    public String getPlainText(int index) {
        return plainTexts.get(index);
    }

    @Override
    public RichParagraph getParagraph(int index) {
        return paragraphs.get(index);
    }

    @Override
    public StyleAttributeMap getStyleAttributeMap(StyleResolver resolver, TextPos pos) {
        // View-only viewer: no per-position style introspection is needed for editing.
        return StyleAttributeMap.EMPTY;
    }
}
