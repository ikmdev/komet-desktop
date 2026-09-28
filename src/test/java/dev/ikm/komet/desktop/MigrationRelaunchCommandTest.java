package dev.ikm.komet.desktop;

import dev.ikm.komet.desktop.LaunchOptions.Option;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The migration relaunch must deliver the proposed folder intact whatever the
 * database is called (IKE-Network/ike-issues#1156): it travels as one program
 * argument, and the relaunched Komet reads it back through {@link LaunchOptions}.
 */
class MigrationRelaunchCommandTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "starter-8bit",
            "My KB-8bit",
            "Keith's KB-8bit",
            "two  spaces-8bit",
            "quote \" and $HOME `x`-8bit",
            "a=b-8bit",
            "accénted ✓-8bit"})
    void folderSurvivesTheRelaunch(String folder) {
        List<String> command = MigrationRelaunchLifecycle.relaunchCommand(new File("/opt/komet/bin/launchKomet"), folder);

        assertEquals(2, command.size(), () -> "one argument after the launcher: " + command);
        assertEquals(new File("/opt/komet/bin/launchKomet").getAbsolutePath(), command.get(0));

        List<String> programArguments = command.subList(1, command.size());
        LaunchOptions options = LaunchOptions.resolve(namedLikeJavaFx(programArguments),
                unnamedLikeJavaFx(programArguments), name -> null);

        assertEquals(Optional.of(folder), options.get(Option.MIGRATION_FOLDER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"My KB-8bit", "Keith's KB-8bit"})
    void systemPropertyStillWorksAsTheFallback(String folder) {
        Map<String, String> properties = Map.of(Option.MIGRATION_FOLDER.propertyName(), folder);

        LaunchOptions options = LaunchOptions.resolve(Map.of(), List.of(), properties::get);

        assertEquals(Optional.of(folder), options.get(Option.MIGRATION_FOLDER));
    }

    // Application.Parameters: an argument "--name=value" is named (split at the first '='),
    // anything else is unnamed — the rule javafx.application.Application documents.
    private static Map<String, String> namedLikeJavaFx(List<String> arguments) {
        Map<String, String> named = new HashMap<>();
        for (String argument : arguments) {
            int equals = argument.indexOf('=');
            if (argument.startsWith("--") && equals > 2) {
                named.put(argument.substring(2, equals), argument.substring(equals + 1));
            }
        }
        return named;
    }

    private static List<String> unnamedLikeJavaFx(List<String> arguments) {
        List<String> unnamed = new ArrayList<>();
        for (String argument : arguments) {
            if (!(argument.startsWith("--") && argument.indexOf('=') > 2)) {
                unnamed.add(argument);
            }
        }
        return unnamed;
    }
}
