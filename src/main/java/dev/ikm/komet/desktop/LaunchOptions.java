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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The command-line options that start Komet on a knowledge base and as a user, with no picker and
 * no author screen (IKE-Network/ike-issues#1139).
 *
 * <p>Each option is a program argument ({@code --kb=<path>}), with a system property
 * ({@code -Dkomet.kb=<path>}) as the fallback for IDE run configurations and {@code JAVA_OPTS}; the
 * program argument wins. The password is never an option: arguments show in {@code ps} and shell
 * history. It comes from the {@value #PASSWORD_ENVIRONMENT_VARIABLE} environment variable (which
 * {@code op run} can supply) or from the file named by {@link Option#PASSWORD_FILE}.
 */
public final class LaunchOptions {

    private static final Logger LOG = LoggerFactory.getLogger(LaunchOptions.class);

    /** The environment variable a {@code --user} password is read from first. */
    public static final String PASSWORD_ENVIRONMENT_VARIABLE = "KOMET_PASSWORD";

    /** The program argument that must never carry a password; it is refused, not read. */
    static final String REFUSED_PASSWORD_ARGUMENT = "password";

    /**
     * The flag that skips reopening the windows open at the last quit, for this launch only
     * (IKE-Network/ike-issues#1151); for example when a journal fails on open.
     */
    public static final String NO_RESTORE_ARGUMENT = "--no-restore";

    /** The system-property fallback for {@link #NO_RESTORE_ARGUMENT}; {@code true} skips reopening. */
    public static final String NO_RESTORE_PROPERTY = "komet.no-restore";

    /**
     * A launch option: its program-argument name and its system-property fallback.
     */
    public enum Option {
        /** The knowledge base to open, skipping the picker. */
        KB("kb", "komet.kb"),
        /** The author to log in as, skipping the author screen when the password checks. */
        USER("user", "komet.user"),
        /** A file whose first line is the {@link #USER} password. */
        PASSWORD_FILE("password-file", "komet.password-file"),
        /**
         * The folder the picker proposes for the new 64-bit database, set only on the relaunch
         * that completes a migration from a legacy layout (IKE-Network/ike-issues#1138, #1258). A program
         * argument, because {@code launchKomet} splits {@code JAVA_OPTS} on whitespace
         * (IKE-Network/ike-issues#1156).
         */
        MIGRATION_FOLDER("migration-folder", "komet.nidLayoutMigration.folder");

        private final String argumentName;
        private final String propertyName;

        Option(String argumentName, String propertyName) {
            this.argumentName = argumentName;
            this.propertyName = propertyName;
        }

        /**
         * The program-argument name, given as {@code --<name>=<value>}.
         *
         * @return the argument name, without the leading dashes
         */
        public String argumentName() {
            return argumentName;
        }

        /**
         * The system-property fallback, given as {@code -D<name>=<value>}.
         *
         * @return the property name
         */
        public String propertyName() {
            return propertyName;
        }
    }

    private static LaunchOptions current = new LaunchOptions(new EnumMap<>(Option.class), false);

    private final Map<Option, String> values;
    private final boolean noRestore;

    private LaunchOptions(Map<Option, String> values, boolean noRestore) {
        this.values = values;
        this.noRestore = noRestore;
    }

    /**
     * Resolves the launch options from the program's named arguments and the system properties.
     * A blank value counts as absent. A {@code --password} argument is refused with an error in
     * the log, never read.
     *
     * @param namedArguments   the {@code --name=value} program arguments, as JavaFX parses them
     * @param unnamedArguments the other program arguments, such as {@value #NO_RESTORE_ARGUMENT}
     * @param systemProperty   looks up a system property; answers {@code null} when it is unset
     * @return the resolved options
     */
    public static LaunchOptions resolve(Map<String, String> namedArguments, List<String> unnamedArguments,
                                        UnaryOperator<String> systemProperty) {
        if (namedArguments.containsKey(REFUSED_PASSWORD_ARGUMENT)) {
            LOG.error("Ignoring --{}: a password is never taken from the command line, where ps and shell "
                    + "history expose it. Set {} or pass --{}.", REFUSED_PASSWORD_ARGUMENT,
                    PASSWORD_ENVIRONMENT_VARIABLE, Option.PASSWORD_FILE.argumentName());
        }
        Map<Option, String> values = new EnumMap<>(Option.class);
        for (Option option : Option.values()) {
            String value = namedArguments.get(option.argumentName());
            if (isBlank(value)) {
                value = systemProperty.apply(option.propertyName());
            }
            if (!isBlank(value)) {
                values.put(option, value.strip());
            }
        }
        boolean noRestore = unnamedArguments.contains(NO_RESTORE_ARGUMENT)
                || Boolean.parseBoolean(systemProperty.apply(NO_RESTORE_PROPERTY));
        return new LaunchOptions(values, noRestore);
    }

    /**
     * The options this process was launched with.
     *
     * @return the current options; none are set until {@link #setCurrent} is called at startup
     */
    public static LaunchOptions current() {
        return current;
    }

    /**
     * Records the options this process was launched with.
     *
     * @param options the resolved options
     */
    static void setCurrent(LaunchOptions options) {
        current = options;
    }

    /**
     * The value given for an option.
     *
     * @param option the option
     * @return its value, or empty when it was not given
     */
    public Optional<String> get(Option option) {
        return Optional.ofNullable(values.get(option));
    }

    /**
     * Whether this launch skips reopening the windows open at the last quit.
     *
     * @return true for {@value #NO_RESTORE_ARGUMENT} or {@code -D}{@value #NO_RESTORE_PROPERTY}{@code =true}
     */
    public boolean noRestore() {
        return noRestore;
    }

    /**
     * The password for {@link Option#USER}: the {@value #PASSWORD_ENVIRONMENT_VARIABLE} environment
     * variable, else the first line of the {@link Option#PASSWORD_FILE} file. An unreadable file
     * is logged and yields no password, so the author screen is shown.
     *
     * @param environment looks up an environment variable; answers {@code null} when it is unset
     * @return the password, or empty when none was supplied
     */
    public Optional<String> password(UnaryOperator<String> environment) {
        String fromEnvironment = environment.apply(PASSWORD_ENVIRONMENT_VARIABLE);
        if (fromEnvironment != null && !fromEnvironment.isEmpty()) {
            return Optional.of(fromEnvironment);
        }
        return get(Option.PASSWORD_FILE).flatMap(LaunchOptions::firstLine);
    }

    private static Optional<String> firstLine(String file) {
        try {
            return Files.readAllLines(Path.of(file), StandardCharsets.UTF_8).stream().findFirst();
        } catch (IOException | RuntimeException ex) {
            LOG.error("Could not read the password file {}", file, ex);
            return Optional.empty();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
