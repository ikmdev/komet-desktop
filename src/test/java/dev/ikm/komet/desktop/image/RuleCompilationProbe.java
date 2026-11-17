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
package dev.ikm.komet.desktop.image;

import dev.ikm.komet.framework.performance.Measures;
import dev.ikm.komet.framework.performance.Topic;
import dev.ikm.komet.framework.performance.Statement;
import dev.ikm.komet.framework.performance.impl.ObservationRecord;
import dev.ikm.komet.framework.rulebase.ListedRuleSet;
import dev.ikm.komet.framework.rulebase.RuleFinding;
import dev.ikm.komet.framework.rulebase.RuleSource;
import dev.ikm.komet.rules.annotated.RulesBase;
import dev.ikm.komet.rules.evrete.EvreteRulesService;
import org.eclipse.collections.api.factory.Lists;
import org.evrete.Configuration;
import org.evrete.KnowledgeService;
import org.evrete.api.Knowledge;
import org.evrete.api.RuleDescriptor;
import org.evrete.api.StatelessSession;
import org.evrete.util.CompilationException;

import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The program {@link RuleCompilationInImageIT} has the jlinked Komet image run. It compiles rule
 * source while it runs, against Komet's own fact types, and fires the rules, the steps a rule
 * written in Komet goes through: compile, add to the rules in use, list, replace after an edit,
 * and refuse source that does not compile. The first cases drive the rule engine itself; the
 * last ones go through Komet's rule service, {@link EvreteRulesService}, as Komet does.
 *
 * <p>It runs as the only classes on the image's class path, so every Komet type, the rule engine
 * and the Java compiler come from the image and from nowhere else. Each case prints
 * {@code BEGIN} and its name, then one line, {@code RESULT}, the case, {@code PASS} or
 * {@code FAIL}, and a detail, separated by tabs; anything a case logs falls between the two.
 *
 * <p>The rule engine services built here are configured as {@code EvreteRulesService} configures
 * Komet's, with literal data disabled, except where a case is about a literal condition.
 */
public final class RuleCompilationProbe {

    /** Where the rules compiled here say they fired: the rule's name and the subject it matched. */
    public static final List<String> FIRED = Collections.synchronizedList(new ArrayList<>());

    static final String BEGIN = "BEGIN";
    static final String RESULT = "RESULT";
    static final String PASS = "PASS";
    static final String FAIL = "FAIL";

    static final String KOMET_TYPES_FROM_IMAGE = "komet-types-from-image";
    static final String COMPILER_IN_IMAGE = "compiler-in-image";
    static final String STORED_SOURCE = "stored-source";
    static final String STORED_SOURCE_ON_RULES_BASE = "stored-source-on-rules-base";
    static final String LITERAL_CONDITION_IN_SOURCE = "literal-condition-in-source";
    static final String LITERAL_CONDITION_IN_BUILDER = "literal-condition-in-builder";
    static final String RULE_ADDED = "rule-added";
    static final String RULE_EDITED = "rule-edited";
    static final String BAD_SOURCE_REFUSED = "bad-source-refused";
    static final String SERVICE_TAKES_RULE_SOURCE = "service-takes-rule-source";
    static final String SERVICE_REFUSES_BAD_SOURCE = "service-refuses-bad-source";

    private static final String JAVA_SOURCE = "JAVA-SOURCE";
    private static final String UNKNOWN_TYPE = "NoSuchKometFact";
    /** The line of {@link #BROKEN_SOURCE} that names the unknown type. */
    private static final int BROKEN_LINE = 7;

    private static final String RULES_BASE_SOURCE = """
            package stored.rules;

            import dev.ikm.komet.desktop.image.RuleCompilationProbe;
            import dev.ikm.komet.framework.performance.impl.ObservationRecord;
            import dev.ikm.komet.rules.annotated.RulesBase;
            import org.evrete.dsl.annotation.MethodPredicate;
            import org.evrete.dsl.annotation.Rule;
            import org.evrete.dsl.annotation.RuleSet;
            import org.evrete.dsl.annotation.Where;

            @RuleSet("Stored rule pack")
            public class StoredPack extends RulesBase {
                @Rule("pack rule")
                @Where(methods = {@MethodPredicate(method = "isComponentFocused", args = {"$observation"})})
                public void componentFocused(ObservationRecord $observation) {
                    RuleCompilationProbe.FIRED.add("pack rule on " + $observation.subject());
                }
            }
            """;

    private static final String LITERAL_SOURCE = """
            package stored.rules;

            import dev.ikm.komet.desktop.image.RuleCompilationProbe;
            import dev.ikm.komet.framework.performance.impl.ObservationRecord;
            import org.evrete.dsl.annotation.Rule;
            import org.evrete.dsl.annotation.Where;

            public class LiteralRules {
                @Rule("literal rule")
                @Where("$observation.topic() == dev.ikm.komet.framework.performance.Topic.COMPONENT_FOCUSED")
                public void fire(ObservationRecord $observation) {
                    RuleCompilationProbe.FIRED.add("literal rule on " + $observation.subject());
                }
            }
            """;

    private static final String BROKEN_SOURCE = """
            package stored.rules;

            import org.evrete.dsl.annotation.Rule;

            public class BrokenRules {
                @Rule("broken rule")
                public void fire(%s $fact) {
                }
            }
            """.formatted(UNKNOWN_TYPE);

    private RuleCompilationProbe() {
    }

    /**
     * Runs every case and prints its result line.
     *
     * @param args not used
     */
    public static void main(String[] args) {
        report(KOMET_TYPES_FROM_IMAGE, RuleCompilationProbe::kometTypesFromImage);
        report(COMPILER_IN_IMAGE, RuleCompilationProbe::compilerInImage);
        report(STORED_SOURCE, RuleCompilationProbe::storedSource);
        report(STORED_SOURCE_ON_RULES_BASE, RuleCompilationProbe::storedSourceOnRulesBase);
        report(LITERAL_CONDITION_IN_SOURCE, RuleCompilationProbe::literalConditionInSource);
        report(LITERAL_CONDITION_IN_BUILDER, RuleCompilationProbe::literalConditionInBuilder);
        report(RULE_ADDED, RuleCompilationProbe::ruleAdded);
        report(RULE_EDITED, RuleCompilationProbe::ruleEdited);
        report(BAD_SOURCE_REFUSED, RuleCompilationProbe::badSourceRefused);
        report(SERVICE_TAKES_RULE_SOURCE, RuleCompilationProbe::serviceTakesRuleSource);
        report(SERVICE_REFUSES_BAD_SOURCE, RuleCompilationProbe::serviceRefusesBadSource);
        // Does not wait for any worker thread a rule engine service left running.
        System.exit(0);
    }

    private static String kometTypesFromImage() {
        List<String> locations = new ArrayList<>();
        for (Class<?> type : List.of(ObservationRecord.class, RulesBase.class, KnowledgeService.class)) {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            String location = source == null ? "no code source" : String.valueOf(source.getLocation());
            if (!location.startsWith("jrt:")) {
                throw new IllegalStateException(type.getName() + " was read from " + location + ", not from the image");
            }
            locations.add(location);
        }
        return String.join(", ", locations);
    }

    private static String compilerInImage() {
        if (ModuleLayer.boot().findModule("jdk.compiler").isEmpty()) {
            throw new IllegalStateException("the image has no jdk.compiler module");
        }
        Object compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("the image has jdk.compiler, but no system Java compiler answers");
        }
        return compiler.getClass().getName();
    }

    private static String storedSource() throws Exception {
        Knowledge knowledge = service(true).newKnowledge()
                .importRules(JAVA_SOURCE, ruleSource("ComponentRules", "component rule", Topic.COMPONENT_FOCUSED));
        return expect(List.of("component rule on component"), fire(knowledge), "the stored rule");
    }

    private static String storedSourceOnRulesBase() throws Exception {
        Knowledge knowledge = service(true).newKnowledge().importRules(JAVA_SOURCE, RULES_BASE_SOURCE);
        return expect(List.of("pack rule on component"), fire(knowledge), "the rule on RulesBase");
    }

    private static String literalConditionInSource() throws Exception {
        Knowledge knowledge = service(false).newKnowledge().importRules(JAVA_SOURCE, LITERAL_SOURCE);
        return expect(List.of("literal rule on component"), fire(knowledge), "the rule with a literal condition");
    }

    private static String literalConditionInBuilder() {
        Knowledge knowledge = service(false).newKnowledge()
                .builder()
                .newRule("builder rule")
                .forEach("$observation", ObservationRecord.class)
                .where("$observation.topic() == dev.ikm.komet.framework.performance.Topic.COMPONENT_FOCUSED")
                .execute(context -> {
                    ObservationRecord observation = context.get("$observation");
                    FIRED.add("builder rule on " + observation.subject());
                })
                .build();
        return expect(List.of("builder rule on component"), fire(knowledge), "the builder rule");
    }

    private static String ruleAdded() throws Exception {
        Knowledge knowledge = service(true).newKnowledge()
                .importRules(JAVA_SOURCE, ruleSource("ComponentRules", "component rule", Topic.COMPONENT_FOCUSED));
        expect(List.of("component rule on component"), fire(knowledge), "before the second rule is added");

        Knowledge extended = knowledge
                .importRules(JAVA_SOURCE, ruleSource("AxiomRules", "axiom rule", Topic.AXIOM_FOCUSED));
        expect(List.of("axiom rule", "component rule"), ruleNames(extended), "the rules the rule set lists");
        return expect(List.of("axiom rule on axiom", "component rule on component"), fire(extended),
                "after the second rule is added");
    }

    private static String ruleEdited() throws Exception {
        // One service throughout, as in a Komet that stays open while a rule is edited.
        KnowledgeService service = service(true);
        Knowledge before = service.newKnowledge()
                .importRules(JAVA_SOURCE, ruleSource("EditedRules", "edited rule", Topic.COMPONENT_FOCUSED));
        expect(List.of("edited rule on component"), fire(before), "before the edit");

        Knowledge after = service.newKnowledge()
                .importRules(JAVA_SOURCE, ruleSource("EditedRules", "edited rule", Topic.AXIOM_FOCUSED));
        return expect(List.of("edited rule on axiom"), fire(after), "after the edit, under the same class name");
    }

    private static String badSourceRefused() throws Exception {
        Knowledge knowledge = service(true).newKnowledge()
                .importRules(JAVA_SOURCE, ruleSource("ComponentRules", "component rule", Topic.COMPONENT_FOCUSED));
        String refusal = null;
        try {
            knowledge.importRules(JAVA_SOURCE, BROKEN_SOURCE);
        } catch (Exception e) {
            refusal = messages(e);
        }
        if (refusal == null) {
            throw new IllegalStateException("source that names the unknown type " + UNKNOWN_TYPE + " was accepted");
        }
        if (!refusal.contains(UNKNOWN_TYPE)) {
            throw new IllegalStateException("the refusal does not carry the compiler's message: " + refusal);
        }
        expect(List.of("component rule"), ruleNames(knowledge), "the rules listed after the refusal");
        expect(List.of("component rule on component"), fire(knowledge), "the rules that fire after the refusal");
        return refusal;
    }

    private static String serviceTakesRuleSource() throws Exception {
        EvreteRulesService rules = new EvreteRulesService();
        ListedRuleSet listed = rules.putRuleSource(
                new RuleSource(UUID.randomUUID(), "Stored rule pack", RULES_BASE_SOURCE));
        if (!listed.inUse()) {
            throw new IllegalStateException("the rule service keeps the source out of use: " + listed.findings());
        }
        expect(List.of("pack rule"), listed.ruleNames().castToList(), "the rules the service lists for the source");
        ListedRuleSet kometsFirst = rules.ruleSets().getFirst();
        if (kometsFirst.origin() != ListedRuleSet.Origin.KOMET || !kometsFirst.inUse()) {
            throw new IllegalStateException("Komet's own rules are not listed first and in use: " + kometsFirst);
        }
        return expect(List.of("pack rule on component"), fire(rules), "through the rule service");
    }

    private static String serviceRefusesBadSource() throws Exception {
        EvreteRulesService rules = new EvreteRulesService();
        rules.putRuleSource(new RuleSource(UUID.randomUUID(), "Stored rule pack", RULES_BASE_SOURCE));
        ListedRuleSet refused = rules.putRuleSource(new RuleSource(UUID.randomUUID(), "Broken rules", BROKEN_SOURCE));
        if (refused.inUse() || refused.findings().size() != 1) {
            throw new IllegalStateException("expected one finding and the source out of use: " + refused);
        }
        RuleFinding finding = refused.findings().getFirst();
        if (finding.line() != BROKEN_LINE || !finding.message().contains(UNKNOWN_TYPE)) {
            throw new IllegalStateException("expected the compiler's message about " + UNKNOWN_TYPE + " at line "
                    + BROKEN_LINE + ": " + finding);
        }
        expect(List.of("pack rule on component"), fire(rules), "the rules that fire after the refusal");
        return "line " + finding.line() + ": " + finding.message();
    }

    /** Rule source as it would be stored: one rule on an observation of a topic, its condition a method. */
    private static String ruleSource(String className, String ruleName, Topic topic) {
        return """
                package stored.rules;

                import dev.ikm.komet.desktop.image.RuleCompilationProbe;
                import dev.ikm.komet.framework.performance.Topic;
                import dev.ikm.komet.framework.performance.impl.ObservationRecord;
                import org.evrete.dsl.annotation.MethodPredicate;
                import org.evrete.dsl.annotation.Rule;
                import org.evrete.dsl.annotation.Where;

                public class %1$s {
                    public boolean onTopic(ObservationRecord $observation) {
                        return $observation.topic() == Topic.%3$s;
                    }

                    @Rule("%2$s")
                    @Where(methods = {@MethodPredicate(method = "onTopic", args = {"$observation"})})
                    public void fire(ObservationRecord $observation) {
                        RuleCompilationProbe.FIRED.add("%2$s on " + $observation.subject());
                    }
                }
                """.formatted(className, ruleName, topic.name());
    }

    private static KnowledgeService service(boolean literalDataDisabled) {
        Configuration configuration = new Configuration();
        if (literalDataDisabled) {
            configuration.set(Configuration.DISABLE_LITERAL_DATA, "true");
        }
        return new KnowledgeService(configuration);
    }

    /** Fires the rules on one focused component and one focused axiom; answers what fired, sorted. */
    private static List<String> fire(Knowledge knowledge) {
        FIRED.clear();
        StatelessSession session = knowledge.newStatelessSession();
        session.insert(List.of(
                new ObservationRecord(Topic.COMPONENT_FOCUSED, "component", Measures.present()),
                new ObservationRecord(Topic.AXIOM_FOCUSED, "axiom", Measures.present())));
        session.fire((handle, fact) -> {
        });
        List<String> fired = new ArrayList<>(FIRED);
        Collections.sort(fired);
        return fired;
    }

    /** Fires Komet's rule service on the same two observations; answers what the rules here fired, sorted. */
    private static List<String> fire(EvreteRulesService rules) {
        FIRED.clear();
        // No view and no edit coordinate: these rules read no knowledge base and write nothing.
        rules.execute("Rule compilation probe", Lists.immutable.<Statement>of(
                new ObservationRecord(Topic.COMPONENT_FOCUSED, "component", Measures.present()),
                new ObservationRecord(Topic.AXIOM_FOCUSED, "axiom", Measures.present())), null, null);
        List<String> fired = new ArrayList<>(FIRED);
        Collections.sort(fired);
        return fired;
    }

    private static List<String> ruleNames(Knowledge knowledge) {
        List<String> names = new ArrayList<>();
        for (RuleDescriptor rule : knowledge.getRules()) {
            names.add(rule.getName());
        }
        Collections.sort(names);
        return names;
    }

    private static String expect(List<String> expected, List<String> actual, String what) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(what + ": expected " + expected + " but got " + actual);
        }
        return what + ": " + actual;
    }

    /** Every message along the cause chain, with the compiler's own lines where there are any. */
    private static String messages(Throwable thrown) {
        List<String> messages = new ArrayList<>();
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            messages.add(String.valueOf(cause));
            if (cause instanceof CompilationException compilation) {
                compilation.getErrorSources()
                        .forEach(source -> messages.addAll(compilation.getErrorMessage(source)));
            }
        }
        return String.join(" | ", messages);
    }

    private interface Check {
        String run() throws Exception;
    }

    private static void report(String probeCase, Check check) {
        System.out.println(BEGIN + "\t" + probeCase);
        String outcome;
        String detail;
        try {
            detail = check.run();
            outcome = PASS;
        } catch (Throwable thrown) {
            detail = messages(thrown);
            outcome = FAIL;
        }
        System.out.println(String.join("\t", RESULT, probeCase, outcome, detail.replaceAll("\\s+", " ")));
    }
}
