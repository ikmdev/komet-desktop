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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rules compiled while Komet runs, checked where Komet runs: in the jlinked image. A rule written
 * in Komet is compiled against Komet's own types, and in the image those types exist only inside
 * the image, with no jar for a compiler to read. Finding them there failed in the past, and a
 * test on Maven's class path cannot see that failure, so this one has the image's own
 * {@code java} run {@link RuleCompilationProbe} and reads back what it reports.
 *
 * <p>The image is the one {@code package} builds, at {@code target/kometRuntimeImage}; the
 * {@code komet.image.directory} system property names another. The test fails, rather than
 * being skipped, when there is no image.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuleCompilationInImageIT {

    private static final String IMAGE_PROPERTY = "komet.image.directory";

    private final Map<String, String> outcomes = new HashMap<>();
    /** What each case printed between its BEGIN and RESULT lines: whatever it logged. */
    private final Map<String, String> logged = new HashMap<>();
    private String output = "";

    @BeforeAll
    void runTheProbeInTheImage(@TempDir Path scratch) throws Exception {
        Path image = Path.of(System.getProperty(IMAGE_PROPERTY, "target/kometRuntimeImage")).toAbsolutePath();
        Path java = Stream.of("java", "java.exe")
                .map(image.resolve("bin")::resolve)
                .filter(Files::isRegularFile)
                .findFirst()
                .orElse(null);
        assertNotNull(java, () -> "No jlinked Komet image at " + image
                + ". The package phase of komet-desktop builds it; this test runs after that phase.");

        Path classPath = probeClassesAlone(scratch.resolve("classes"));
        Path log = scratch.resolve("probe-output.txt");
        Process probe = new ProcessBuilder(List.of(java.toString(),
                "--enable-preview", "-cp", classPath.toString(), RuleCompilationProbe.class.getName()))
                .directory(scratch.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        boolean ended = probe.waitFor(2, TimeUnit.MINUTES);
        if (!ended) {
            probe.destroyForcibly().waitFor();
        }
        output = Files.readString(log);
        assertTrue(ended, () -> "The probe did not end within two minutes. Its output:\n" + output);

        String begun = null;
        StringBuilder sinceBegin = new StringBuilder();
        for (String line : output.lines().toList()) {
            String[] fields = line.split("\t", 4);
            if (fields.length == 2 && fields[0].equals(RuleCompilationProbe.BEGIN)) {
                begun = fields[1];
                sinceBegin.setLength(0);
            } else if (fields.length == 4 && fields[0].equals(RuleCompilationProbe.RESULT)) {
                outcomes.put(fields[1], fields[2] + " " + fields[3]);
                if (fields[1].equals(begun)) {
                    logged.put(begun, sinceBegin.toString());
                }
            } else {
                sinceBegin.append(line).append('\n');
            }
        }
    }

    @Test
    void kometsTypesAndTheRuleEngineAreReadFromTheImage() {
        assertPassed(RuleCompilationProbe.KOMET_TYPES_FROM_IMAGE);
    }

    @Test
    void theImageCarriesTheJavaCompiler() {
        assertPassed(RuleCompilationProbe.COMPILER_IN_IMAGE);
    }

    @Test
    void storedRuleSourceCompilesAgainstAKometFactAndFires() {
        assertPassed(RuleCompilationProbe.STORED_SOURCE);
    }

    @Test
    void storedRuleSourceMayExtendRulesBase() {
        assertPassed(RuleCompilationProbe.STORED_SOURCE_ON_RULES_BASE);
    }

    @Test
    void aLiteralConditionInStoredSourceCompilesAndFires() {
        assertPassed(RuleCompilationProbe.LITERAL_CONDITION_IN_SOURCE);
    }

    @Test
    void aLiteralConditionInABuilderRuleCompilesAndFires() {
        assertPassed(RuleCompilationProbe.LITERAL_CONDITION_IN_BUILDER);
    }

    @Test
    void aRuleAddedToRulesInUseIsListedAndFiresBesideThem() {
        assertPassed(RuleCompilationProbe.RULE_ADDED);
    }

    @Test
    void anEditedRuleCompiledUnderTheSameNameFiresAsEdited() {
        assertPassed(RuleCompilationProbe.RULE_EDITED);
    }

    @Test
    void sourceThatDoesNotCompileIsRefusedWithTheCompilersMessageAndTheOtherRulesStay() {
        assertPassed(RuleCompilationProbe.BAD_SOURCE_REFUSED);
    }

    @Test
    void kometsRuleServiceCompilesARuleSourceAndFiresItsRule() {
        assertPassed(RuleCompilationProbe.SERVICE_TAKES_RULE_SOURCE);
    }

    @Test
    void kometsRuleServiceRefusesBadSourceWithTheCompilersLineAndKeepsFiring() {
        assertPassed(RuleCompilationProbe.SERVICE_REFUSES_BAD_SOURCE);
    }

    @Test
    void kometsRuleServiceReportsBadSourceAsAFindingWithoutTheRuleEngineLoggingIt() {
        String duringTheRefusal = logged.get(RuleCompilationProbe.SERVICE_REFUSES_BAD_SOURCE);
        assertNotNull(duringTheRefusal, () -> "The probe did not run that case. Its output:\n" + output);
        // The rule engine's own import of source logs the whole source at SEVERE when it does not compile.
        assertFalse(duringTheRefusal.contains("SEVERE"), () -> "Logged during the refusal:\n" + duringTheRefusal);
    }

    private void assertPassed(String probeCase) {
        String outcome = outcomes.get(probeCase);
        assertNotNull(outcome, () -> "The probe reported nothing for " + probeCase + ". Its output:\n" + output);
        assertTrue(outcome.startsWith(RuleCompilationProbe.PASS),
                () -> probeCase + ": " + outcome + "\nThe probe's whole output:\n" + output);
    }

    /**
     * Copies the probe's class files, and no others, to a class path of their own, so nothing
     * else this module's tests bring along is there for the image to read.
     */
    private static Path probeClassesAlone(Path classPath) throws IOException, URISyntaxException {
        Class<?> probe = RuleCompilationProbe.class;
        Path packagePath = Path.of(probe.getPackageName().replace('.', '/'));
        Path compiled = Path.of(probe.getProtectionDomain().getCodeSource().getLocation().toURI())
                .resolve(packagePath);
        Path target = Files.createDirectories(classPath.resolve(packagePath));
        try (Stream<Path> files = Files.list(compiled)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith(probe.getSimpleName()) && name.endsWith(".class")) {
                    Files.copy(file, target.resolve(name));
                }
            }
        }
        return classPath;
    }
}
