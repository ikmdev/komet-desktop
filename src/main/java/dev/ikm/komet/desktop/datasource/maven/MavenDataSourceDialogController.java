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
package dev.ikm.komet.desktop.datasource.maven;

import dev.ikm.komet.framework.concurrent.TaskWrapper;
import dev.ikm.komet.framework.progress.ProgressHelper;
import dev.ikm.komet.pluginresolver.ArtifactCoordinates;
import dev.ikm.komet.pluginresolver.Credentials;
import dev.ikm.komet.pluginresolver.DialogSelection;
import dev.ikm.komet.pluginresolver.DialogSelectionStore;
import dev.ikm.komet.pluginresolver.KometPreferencesCredentialStore;
import dev.ikm.komet.pluginresolver.KometPreferencesDialogSelectionStore;
import dev.ikm.komet.pluginresolver.LocalRepositorySearch;
import dev.ikm.komet.pluginresolver.LocalVersionResolver;
import dev.ikm.komet.pluginresolver.MavenDataStoreDownloadTask;
import dev.ikm.komet.pluginresolver.NexusSearchClient;
import dev.ikm.komet.pluginresolver.RemoteVersionResolver;
import dev.ikm.komet.pluginresolver.RepositoryConnectionTester;
import dev.ikm.komet.pluginresolver.RepositoryCredentialStore;
import dev.ikm.komet.pluginresolver.SettingsXmlReader;
import dev.ikm.tinkar.common.service.DataUriOption;
import dev.ikm.tinkar.common.service.TinkExecutor;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Controller for the "Add from Maven" dialog: enter/confirm a groupId:artifactId, browse its
 * published versions, pick one, and download it into {@code ~/Solor} — either unpacked as a
 * SpinedArray store snapshot ({@link ProviderArtifactQualifier.Flow#SA}) or placed as a
 * protobuf changeset zip ({@link ProviderArtifactQualifier.Flow#PB}), per the flow the dialog
 * was opened for. On success, the resulting {@link DataUriOption} is available via
 * {@link #result()} — an ordinary local entry, exactly as if the user had placed it under
 * {@code ~/Solor} by hand.
 *
 * <p>The repository field is pre-filled from {@code ~/.m2/settings.xml} (structural read only
 * — see {@link SettingsXmlReader}), and credentials are read from / saved to
 * {@link RepositoryCredentialStore}, keyed by repository id.
 */
public final class MavenDataSourceDialogController {

    private static final Logger LOG = LoggerFactory.getLogger(MavenDataSourceDialogController.class);
    private static final String DEFAULT_REPOSITORY_URL = "https://repo.maven.apache.org/maven2/";
    private static final String DEFAULT_REPOSITORY_ID = "central";

    /** Default repository — pre-selected whenever no prior selection has been remembered yet. */
    private static final String KNOWN_WORKING_REPOSITORY_URL = "https://nexus.tinkar.org/repository/ike-restricted/";
    private static final String KNOWN_WORKING_REPOSITORY_ID = "ike-restricted";

    /**
     * A pseudo "repository URL" selectable in {@link #repositoryUrlComboBox} that means
     * "browse {@code ~/.m2/repository} on disk" instead of any network repository — no
     * credentials, no HTTP, just a filesystem walk ({@link LocalRepositorySearch},
     * {@link LocalVersionResolver}). Stored and remembered exactly like a real URL would be
     * (see {@link DialogSelection#repositoryUrl}); {@link #isLocal} is the only place that
     * distinguishes it.
     */
    private static final String LOCAL_REPOSITORY_SENTINEL = "Local repository (~/.m2)";

    private static final Path M2_REPOSITORY_ROOT = Path.of(System.getProperty("user.home"), ".m2", "repository");

    @FXML
    private TextField nexusSearchField;
    @FXML
    private Button nexusSearchButton;
    @FXML
    private ListView<ArtifactCoordinates> nexusSearchResultsView;
    @FXML
    private TextField groupIdField;
    @FXML
    private TextField artifactIdField;
    @FXML
    private ComboBox<String> repositoryUrlComboBox;
    @FXML
    private TextField usernameField;
    @FXML
    private PasswordField passwordField;
    @FXML
    private Button testConnectionButton;
    @FXML
    private CheckBox rememberCredentialsCheckBox;
    @FXML
    private ComboBox<String> versionComboBox;
    @FXML
    private TextField classifierField;
    @FXML
    private Label statusLabel;
    @FXML
    private Label sizeLabel;
    @FXML
    private Label existingArtifactLabel;
    @FXML
    private Label freshnessLabel;
    @FXML
    private Button downloadButton;
    @FXML
    private ProgressBar downloadProgressBar;
    @FXML
    private Label etaLabel;
    @FXML
    private Button cancelDownloadButton;
    @FXML
    private Button closeButton;

    private final RepositoryCredentialStore credentialStore = new KometPreferencesCredentialStore();
    private final DialogSelectionStore dialogSelectionStore = new KometPreferencesDialogSelectionStore();
    private final Map<String, String> repositoryIdByUrl = new HashMap<>();

    private ProviderArtifactQualifier.Flow flow;
    private Stage stage;
    private DataUriOption result;
    private MavenDataStoreDownloadTask activeTask;
    private boolean listingVersions;

    /**
     * For each of {@link #nexusSearchResultsView}'s current items, the specific version
     * {@link #nexusSearchButtonPressed}'s classifier-scoped search confirmed publishes a
     * compatible classifier — populated fresh on every search (empty for a {@link #LOCAL_REPOSITORY_SENTINEL}
     * search, which doesn't track this). Consulted by {@link #listVersions(Optional)} so picking a
     * search result preselects a version already known to work, instead of blindly "most recent"
     * (which is not guaranteed to be the version that made the artifact match in the first place —
     * see {@link #listVersions(Optional)}'s javadoc).
     */
    private Map<ArtifactCoordinates, String> searchCompatibleVersionByCoordinates = Map.of();

    /**
     * The classifier {@link #probeSize} most recently resolved as actually present for the
     * currently-selected version — {@code null} until a probe succeeds. Reset to {@code null}
     * at the start of every probe so a stale value from a previous version can never leak into
     * a download for a different one.
     */
    private String resolvedClassifier;

    /**
     * The concrete file version {@link #probeSize} most recently resolved for the
     * currently-selected version — the same string for a release, but the concrete,
     * uniquely-timestamped file version (e.g. {@code "1.0.0-20260714.135548-4"}) for a
     * {@code -SNAPSHOT} version, since that's what a snapshot repository's published filenames
     * actually embed (see {@code MavenDataStoreDownloadTask}'s directory-version/file-version
     * overloads). {@code null} until a probe succeeds; reset alongside {@link #resolvedClassifier}.
     */
    private String resolvedFileVersion;

    /**
     * The resolved zip classifier's expected SHA-256, per the same {@link NexusSearchClient
     * #componentAssets} lookup that resolved {@link #resolvedClassifier} — {@link Optional#empty()}
     * for {@link #LOCAL_REPOSITORY_SENTINEL} (no network round trip to get one from) or if Nexus
     * simply didn't report one. Passed to {@link MavenDataStoreDownloadTask} so a corrupted or
     * tampered download is caught before it's ever unpacked into {@code ~/Solor}.
     */
    private Optional<String> resolvedZipSha256 = Optional.empty();

    /**
     * The artifact's main POM asset info, from the same lookup — used to also fetch/cache/verify
     * the POM alongside the zip (see {@link MavenDataStoreDownloadTask.AssetVerification#pom}),
     * matching what real Maven tooling does and what {@code LocalRepositorySearch}/
     * {@code LocalVersionResolver} prefer to find. Empty for {@link #LOCAL_REPOSITORY_SENTINEL}
     * or if this artifact simply has no published POM.
     */
    private Optional<NexusSearchClient.AssetInfo> resolvedPom = Optional.empty();

    /**
     * The {@link #describeExistingDestinationFreshness} result last computed by {@link #probeSize}
     * for the currently-resolved version — only meaningful when the prospective destination
     * actually exists; reset to {@link ExistingArtifactFreshness#UNKNOWN} at the start of every new
     * probe so a stale value can never leak into a different version's display. Computed off the
     * JavaFX Application Thread (it may hash an entire unpacked directory's on-disk content — real
     * time for a large store) and cached here so {@link #resolveDownloadArtifactName}'s confirmation
     * dialog can reuse it verbatim instead of recomputing synchronously on the FX thread when
     * Download is clicked.
     */
    private ExistingArtifactFreshness resolvedFreshness = ExistingArtifactFreshness.UNKNOWN;

    /**
     * Shows the dialog modally and waits for it to close.
     *
     * @param owner the owner window
     * @param flow which download flow this dialog instance is for
     * @return the resolved {@link DataUriOption}, or empty if the user closed the dialog
     *         without completing a download
     * @throws IOException if the dialog's FXML cannot be loaded
     * @throws NullPointerException if {@code flow} is {@code null}
     */
    public static Optional<DataUriOption> show(Stage owner, ProviderArtifactQualifier.Flow flow) throws IOException {
        Objects.requireNonNull(flow, "flow");
        FXMLLoader loader = new FXMLLoader(MavenDataSourceDialogController.class.getResource("MavenDataSourceDialog.fxml"));
        Parent root = loader.load();
        MavenDataSourceDialogController controller = loader.getController();
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(switch (flow) {
            case SA -> "Download SpinedArray store from Maven";
            case ROCKS -> "Download Rocks KB store from Maven";
            case PB -> "Download IKE Protobuf from Maven";
        });
        stage.setScene(new Scene(root));
        controller.applyFlow(flow);
        controller.stage = stage;
        stage.showAndWait();
        return controller.result();
    }

    /**
     * Records which flow this dialog instance is for and restores the last-used repository
     * ({@link DialogSelection}) — falling back to {@link #KNOWN_WORKING_REPOSITORY_URL} when
     * nothing has been remembered yet — so a returning user doesn't re-pick a repository or
     * re-enter credentials. If credentials are already known (restored from
     * {@link #credentialStore} the moment the repository was set above), this immediately tests
     * them, so the dialog opens already showing "Connection succeeded".
     *
     * <p>Group Id, Artifact Id, and Version are deliberately left <em>empty</em>: the dialog must
     * not open already naming — and, with {@link #downloadButton} enabled, already offering — an
     * artifact the user hasn't chosen in this session. Choosing one is always an explicit act,
     * whether by searching or by typing coordinates directly.
     *
     * @param flow the flow to apply
     */
    private void applyFlow(ProviderArtifactQualifier.Flow flow) {
        this.flow = flow;
        // The classifier field always restates the new flow's own candidates: a value hand-entered
        // for the previous flow describes artifacts this one doesn't use, and silently carrying it
        // over would search for the wrong thing (ikmdev/komet-desktop#117).
        classifierField.setText(ClassifierSelection.ofFlowDefaults(flow).displayText());
        Optional<DialogSelection> remembered = dialogSelectionStore.get(flow.name());

        String repositoryUrl = remembered.map(DialogSelection::repositoryUrl)
                .filter(value -> !value.isBlank())
                .orElse(KNOWN_WORKING_REPOSITORY_URL);
        repositoryUrlComboBox.setValue(repositoryUrl);

        if (!isLocal(repositoryUrl) && enteredCredentials().isPresent()) {
            testConnectionButtonPressed();
        }
    }

    /**
     * Whether {@code repositoryUrl} is {@link #LOCAL_REPOSITORY_SENTINEL} — browsing
     * {@code ~/.m2/repository} on disk rather than a network repository.
     *
     * @param repositoryUrl the value currently selected in {@link #repositoryUrlComboBox}
     * @return {@code true} if this is the local-repository source
     */
    private static boolean isLocal(String repositoryUrl) {
        return LOCAL_REPOSITORY_SENTINEL.equals(repositoryUrl);
    }

    /**
     * The resolved {@link DataUriOption}, once the dialog has closed.
     *
     * @return the resolved option, or empty if no download completed
     */
    public Optional<DataUriOption> result() {
        return Optional.ofNullable(result);
    }

    @FXML
    private void initialize() {
        downloadButton.setDisable(true);
        downloadProgressBar.setVisible(false);
        etaLabel.setVisible(false);
        cancelDownloadButton.setVisible(false);

        initializeRepositoryChoices();
        repositoryUrlComboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            updateCredentialControlsForRepository(newValue);
            if (isLocal(newValue)) {
                usernameField.clear();
                passwordField.clear();
            } else {
                loadCredentialsFor(newValue);
            }
            resetConnectionTestState();
        });
        usernameField.textProperty().addListener((observable, oldValue, newValue) -> resetConnectionTestState());
        passwordField.textProperty().addListener((observable, oldValue, newValue) -> resetConnectionTestState());
        passwordField.setOnAction(event -> testConnectionButtonPressed());
        passwordField.focusedProperty().addListener((observable, wasFocused, isNowFocused) -> {
            if (!isNowFocused) {
                testConnectionButtonPressed();
            }
        });

        nexusSearchResultsView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                groupIdField.setText(newValue.groupId());
                artifactIdField.setText(newValue.artifactId());
                // setText() is programmatic, so groupIdField/artifactIdField's own
                // focus-lost/Enter triggers below never fire for it — list explicitly.
                listVersions(Optional.ofNullable(searchCompatibleVersionByCoordinates.get(newValue)));
            }
        });
        // Enter (or the Search button) runs the search — deliberately NOT focus-lost. Clicking a
        // result moves focus OUT of this field, and a focus-lost re-search there would run
        // concurrently with the result-selection handler above, its clearSelectedArtifactFields()
        // wiping the Group Id / Artifact Id that the click just set (the "click doesn't stick"
        // race). The button and Enter are the only sensible triggers anyway.
        nexusSearchField.setOnAction(event -> nexusSearchButtonPressed());

        // Same reasoning for the coordinate fields: Enter lists versions; focus-lost does not,
        // so tabbing/clicking between these fields and the version picker can't fire a stray
        // listVersions mid-interaction. A user typing coordinates presses Enter (or edits the
        // version field, which lists on its own) to trigger it.
        groupIdField.setOnAction(event -> listVersions());
        artifactIdField.setOnAction(event -> listVersions());

        // Editable ComboBox<String> already commits typed text into valueProperty on Enter or
        // focus-lost (same behavior repositoryUrlComboBox already relies on above), so one
        // listener covers both picking from the list and typing a version manually — no
        // separate onAction/focus wiring needed the way the old plain TextField required.
        versionComboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            // Disabled until probeSize confirms a compatible classifier actually exists for
            // this version — a version with no such variant must not be downloadable, per
            // IKE-Network/ike-issues#882 ("don't present an artifact as an option when no
            // compatible classifier exists for it").
            downloadButton.setDisable(true);
            if (newValue != null && !newValue.isBlank()) {
                probeSize(newValue.strip());
            } else {
                sizeLabel.setText("");
            }
        });
    }

    /**
     * Enables/disables every credential-related control based on whether {@code repositoryUrl}
     * is {@link #LOCAL_REPOSITORY_SENTINEL} — browsing {@code ~/.m2} needs no repository
     * connection at all.
     *
     * @param repositoryUrl the value currently selected in {@link #repositoryUrlComboBox}
     */
    private void updateCredentialControlsForRepository(String repositoryUrl) {
        boolean local = isLocal(repositoryUrl);
        usernameField.setDisable(local);
        passwordField.setDisable(local);
        testConnectionButton.setDisable(local);
        rememberCredentialsCheckBox.setDisable(local);
        if (local) {
            statusLabel.setText("Browsing ~/.m2 — no repository connection needed.");
        }
    }

    /**
     * Populates {@link #repositoryUrlComboBox}'s available choices: the known-working
     * repository, Central, and everything declared in {@code ~/.m2/settings.xml}'s
     * {@code <repositories>}/{@code <mirrors>}, remembering each URL's repository id for
     * credential lookup. Does not select a value — {@link #applyFlow} decides that once the
     * flow (and any remembered selection for it) is known.
     */
    private void initializeRepositoryChoices() {
        repositoryUrlComboBox.getItems().add(LOCAL_REPOSITORY_SENTINEL);
        repositoryIdByUrl.put(KNOWN_WORKING_REPOSITORY_URL, KNOWN_WORKING_REPOSITORY_ID);
        repositoryUrlComboBox.getItems().add(KNOWN_WORKING_REPOSITORY_URL);
        repositoryIdByUrl.put(DEFAULT_REPOSITORY_URL, DEFAULT_REPOSITORY_ID);
        repositoryUrlComboBox.getItems().add(DEFAULT_REPOSITORY_URL);

        Path settingsXmlPath = Path.of(System.getProperty("user.home"), ".m2", "settings.xml");
        try {
            SettingsXmlReader.Settings settings = SettingsXmlReader.read(settingsXmlPath);
            for (SettingsXmlReader.RemoteRepositoryDescriptor repository : settings.repositories()) {
                if (repository.url() != null && !repositoryUrlComboBox.getItems().contains(repository.url())) {
                    repositoryUrlComboBox.getItems().add(repository.url());
                    repositoryIdByUrl.put(repository.url(), repository.id());
                }
            }
            for (SettingsXmlReader.Mirror mirror : settings.mirrors()) {
                if (mirror.url() != null && !repositoryUrlComboBox.getItems().contains(mirror.url())) {
                    repositoryUrlComboBox.getItems().add(mirror.url());
                    repositoryIdByUrl.put(mirror.url(), mirror.id());
                }
            }
        } catch (IOException e) {
            LOG.debug("No usable settings.xml at {} — using the built-in repositories only", settingsXmlPath, e);
        }
    }

    /**
     * The repository id for a URL — the id {@code settings.xml} declared it under, or the URL
     * itself as a fallback key for a manually-typed repository not found there.
     *
     * @param repositoryUrl the repository URL
     * @return the repository id to key credential storage with
     */
    /**
     * The classifiers to look for: whatever the user has the classifier field set to, falling back
     * to this flow's own candidates when they've cleared it (ikmdev/komet-desktop#117).
     *
     * @return the current selection, never empty
     */
    private ClassifierSelection classifierSelection() {
        ClassifierSelection edited = ClassifierSelection.parse(classifierField.getText());
        return edited.isEmpty() ? ClassifierSelection.ofFlowDefaults(flow) : edited;
    }

    /**
     * The classifier names a repository <em>search</em> is scoped by. A search filters on exact
     * names and cannot evaluate a glob, so a selection of nothing but wildcards leaves the browse
     * step with no scope — this flow's candidates stand in, and the wildcards still apply where
     * they can be evaluated: against the classifiers a specific version actually publishes.
     *
     * @return the classifier names to browse by
     */
    private List<String> browseClassifiers() {
        List<String> literals = classifierSelection().literals();
        return literals.isEmpty() ? ProviderArtifactQualifier.classifierCandidates(flow) : literals;
    }

    private String repositoryId(String repositoryUrl) {
        return repositoryIdByUrl.getOrDefault(repositoryUrl, repositoryUrl);
    }

    private void loadCredentialsFor(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            usernameField.clear();
            passwordField.clear();
            return;
        }
        Optional<Credentials> stored = credentialStore.get(repositoryId(repositoryUrl));
        usernameField.setText(stored.map(Credentials::username).orElse(""));
        passwordField.setText(stored.map(credentials -> new String(credentials.password())).orElse(""));
    }

    /**
     * The credentials currently entered, if both fields are non-blank once stripped.
     *
     * <p>Both fields are stripped of leading/trailing whitespace, including the password —
     * pasting into {@link #passwordField} from almost any source (a password manager's copy
     * button, a terminal, a text file) commonly carries a trailing newline that's invisible in
     * the masked field but breaks Basic Auth outright, since the server's real password has no
     * such trailing character. Typing manually never hits this, which is why the symptom looks
     * like intermittent failure rather than a consistent one.
     *
     * @return the entered credentials, or empty if either field is blank once stripped
     */
    private Optional<Credentials> enteredCredentials() {
        String username = usernameField.getText();
        String password = passwordField.getText();
        if (username == null || password == null) {
            return Optional.empty();
        }
        String strippedUsername = username.strip();
        String strippedPassword = password.strip();
        if (strippedUsername.isEmpty() || strippedPassword.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Credentials(strippedUsername, strippedPassword.toCharArray()));
    }

    /**
     * Tests whether the current repository URL accepts the currently entered credentials (or,
     * if blank, an anonymous request) via a lightweight {@code HEAD} probe of the repository's
     * base URL. Updates {@link #testConnectionButton}'s own text/color to reflect the outcome —
     * black "Test Connection" while idle, green "Connection succeeded", or red
     * "Connection failed". Triggered by the button itself, and by tabbing out of or pressing
     * Return in {@link #passwordField} ({@link #initialize}).
     *
     * <p>A successful test with non-blank credentials is itself proof the credentials are
     * correct, so — if {@link #rememberCredentialsCheckBox} is checked — they're saved to
     * {@link #credentialStore} right here, rather than only ever being saved after a full
     * download completes; requiring a whole download just to remember already-verified
     * credentials would be needless friction.
     */
    @FXML
    private void testConnectionButtonPressed() {
        if (testConnectionButton.isDisabled()) {
            // A test is already in flight — button click, Enter, and tab-out can all reach this
            // method independently; without this guard two overlapping requests could race, and
            // whichever response lands last (even a stale, transiently-failed one) would
            // silently win.
            return;
        }
        String repositoryUrl = repositoryUrlComboBox.getValue();
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            return;
        }
        Optional<Credentials> credentials = enteredCredentials();

        testConnectionButton.setDisable(true);

        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                HttpClient httpClient = HttpClient.newHttpClient();
                return credentials.isPresent()
                        ? RepositoryConnectionTester.testConnection(httpClient, repositoryUrl, credentials.get())
                        : RepositoryConnectionTester.testConnection(httpClient, repositoryUrl);
            }
        };
        task.setOnSucceeded(event -> {
            testConnectionButton.setDisable(false);
            boolean succeeded = task.getValue();
            testConnectionButton.setText(succeeded ? "Connection succeeded" : "Connection failed");
            testConnectionButton.setStyle(succeeded ? "-fx-text-fill: green;" : "-fx-text-fill: red;");
            if (succeeded && rememberCredentialsCheckBox.isSelected()) {
                credentials.ifPresent(creds -> credentialStore.put(repositoryId(repositoryUrl), creds));
            }
        });
        task.setOnFailed(event -> {
            testConnectionButton.setDisable(false);
            testConnectionButton.setText("Connection failed");
            testConnectionButton.setStyle("-fx-text-fill: red;");
            statusLabel.setText("Connection test failed: " + task.getException().getMessage());
        });
        TinkExecutor.threadPool().submit(task);
    }

    /**
     * Resets {@link #testConnectionButton} back to its idle "Test Connection" / black state —
     * the previous test result no longer reflects the current repository/username/password.
     */
    private void resetConnectionTestState() {
        testConnectionButton.setText("Test Connection");
        testConnectionButton.setStyle("-fx-text-fill: black;");
    }

    /**
     * A {@link #nexusSearchButtonPressed} search's results — the matching groupId:artifactId
     * pairs, plus (for a remote/Nexus search only) which specific version of each was confirmed
     * to publish a compatible classifier, per {@link NexusSearchClient.SearchMatch}. Empty for
     * {@link #LOCAL_REPOSITORY_SENTINEL} — {@link LocalRepositorySearch} doesn't track this.
     */
    private record SearchOutcome(List<ArtifactCoordinates> coordinates, Map<ArtifactCoordinates, String> compatibleVersionByCoordinates) {
    }

    /**
     * Free-text searches the selected repository for matching groupId:artifactId pairs that
     * also publish at least one classifier compatible with {@link #flow}
     * ({@link ProviderArtifactQualifier#classifierCandidates}) — so browsing doesn't require the
     * user to already know exact coordinates, and doesn't surface an artifact only to discover,
     * once picked, that it has no usable variant (per IKE-Network/ike-issues#882: an incompatible
     * artifact must not be presented as an option at all). Delegates to
     * {@link NexusSearchClient#searchWithCompatibleVersions} (a fixed, small number of
     * classifier-scoped requests, not one per matching artifact) — which, unlike plain
     * {@link NexusSearchClient#search(HttpClient, String, String, List, Credentials)}, also
     * reports which specific version matched, stashed in {@link #searchCompatibleVersionByCoordinates}
     * for {@link #listVersions(Optional)} to prefer once a result is picked — or
     * {@link LocalRepositorySearch#search(Path, String, List)} for {@link #LOCAL_REPOSITORY_SENTINEL}.
     * Repositories that aren't served by Nexus (e.g. Maven Central) have no equivalent endpoint —
     * any failure here, including "not a Nexus repository", is reported via {@link #statusLabel}
     * rather than treated as an error; the user can still fall back to typing the groupId/artifactId
     * directly. Triggered by the button, by tabbing out of or pressing Return in
     * {@link #nexusSearchField} ({@link #initialize}), matching
     * {@link #testConnectionButtonPressed}'s convention.
     */
    @FXML
    private void nexusSearchButtonPressed() {
        if (nexusSearchButton.isDisabled()) {
            // A search is already in flight — see testConnectionButtonPressed's identical
            // guard for why overlapping triggers need this.
            return;
        }
        String query = nexusSearchField.getText();
        if (query == null || query.isBlank()) {
            return;
        }
        String repositoryUrl = repositoryUrlComboBox.getValue();
        boolean local = isLocal(repositoryUrl);
        Optional<Credentials> credentials = enteredCredentials();
        List<String> classifierCandidates = browseClassifiers();

        nexusSearchButton.setDisable(true);
        statusLabel.setText("Searching…");
        nexusSearchResultsView.getItems().clear();
        // These describe whichever artifact was last probed — meaningless the moment a new
        // search starts, and actively misleading if left up while results for a different
        // artifact are being chosen.
        clearArtifactStatusLabels();
        sizeLabel.setText("");

        Task<SearchOutcome> task = new Task<>() {
            @Override
            protected SearchOutcome call() throws Exception {
                if (local) {
                    List<ArtifactCoordinates> localResults = List.copyOf(LocalRepositorySearch.search(M2_REPOSITORY_ROOT, query.strip(), classifierCandidates));
                    return new SearchOutcome(localResults, Map.of());
                }
                HttpClient httpClient = HttpClient.newHttpClient();
                List<NexusSearchClient.SearchMatch> matches = NexusSearchClient.searchWithCompatibleVersions(
                        httpClient, repositoryUrl, query.strip(), classifierCandidates, credentials.orElse(null));
                Map<ArtifactCoordinates, String> compatibleVersions = new LinkedHashMap<>();
                List<ArtifactCoordinates> coordinates = new ArrayList<>();
                for (NexusSearchClient.SearchMatch match : matches) {
                    coordinates.add(match.coordinates());
                    compatibleVersions.put(match.coordinates(), match.compatibleVersion());
                }
                return new SearchOutcome(List.copyOf(coordinates), Map.copyOf(compatibleVersions));
            }
        };
        task.setOnSucceeded(event -> {
            nexusSearchButton.setDisable(false);
            SearchOutcome outcome = task.getValue();
            searchCompatibleVersionByCoordinates = outcome.compatibleVersionByCoordinates();
            nexusSearchResultsView.getItems().setAll(outcome.coordinates());
            if (!outcome.coordinates().isEmpty()) {
                // Group Id/Artifact Id/Version still describe whatever was probed before this
                // search — a remembered selection from a previous session, or an earlier pick.
                // Leaving them (and an enabled Download) next to a fresh, as-yet-unselected
                // result list means the screen claims one artifact while offering another:
                // pressing Download would fetch the stale one, not anything the user searched
                // for. Clear them so nothing is claimed until a result is actually picked.
                clearSelectedArtifactFields();
            }
            statusLabel.setText(outcome.coordinates().isEmpty()
                    ? "No matches found."
                    : outcome.coordinates().size() + " match(es) found — pick one to fill Group Id / Artifact Id.");
        });
        task.setOnFailed(event -> {
            nexusSearchButton.setDisable(false);
            statusLabel.setText((local ? "Local search failed: " : "Search unavailable for this repository: ")
                    + task.getException().getMessage());
        });
        TinkExecutor.threadPool().submit(task);
    }

    /**
     * Lists the published versions of the current Group Id/Artifact Id and selects the most
     * recent one ({@link RemoteVersionResolver.VersionListing#mostRecent}), which in turn (via
     * {@link #versionComboBox}'s {@code valueProperty} listener in {@link #initialize}) triggers
     * {@link #probeSize}. Triggered automatically — by tabbing out of or pressing Return in
     * {@link #groupIdField}/{@link #artifactIdField}, by picking a Nexus search result
     * ({@link #initialize}), and once from {@link #applyFlow} — never needs a button.
     */
    private void listVersions() {
        listVersions(Optional.empty());
    }

    /**
     * As {@link #listVersions()}, but preferring {@code preferredVersion} for the auto-selection
     * once versions are listed, if it's actually present among them — falling back to "most
     * recent" only when it isn't. {@code preferredVersion} comes from
     * {@link #searchCompatibleVersionByCoordinates}: a Nexus search result is only surfaced at
     * all because <em>some</em> version of it was confirmed to publish a compatible classifier
     * ({@link #nexusSearchButtonPressed}), but that's not necessarily the most recent version —
     * blindly auto-selecting "most recent" here could land on a version with no compatible
     * classifier at all, meaning the artifact was presented as an option only to be immediately
     * unusable once picked (per IKE-Network/ike-issues#882, exactly what classifier-scoped search
     * was meant to prevent).
     *
     * @param preferredVersion a version already confirmed compatible, if this call originated
     *         from picking a search result; empty for every other trigger (typing Group Id/
     *         Artifact Id directly, {@link #applyFlow})
     */
    private void listVersions(Optional<String> preferredVersion) {
        if (listingVersions) {
            // Both fields' tab-out/Enter triggers, plus applyFlow's initial call, can all
            // reach this independently — see testConnectionButtonPressed's identical guard.
            return;
        }
        String groupId = groupIdField.getText();
        String artifactId = artifactIdField.getText();
        if (groupId == null || groupId.isBlank() || artifactId == null || artifactId.isBlank()) {
            return;
        }
        ArtifactCoordinates coordinates = new ArtifactCoordinates(groupId.strip(), artifactId.strip());
        String repositoryUrl = repositoryUrlComboBox.getValue();
        boolean local = isLocal(repositoryUrl);
        Optional<Credentials> credentials = enteredCredentials();

        listingVersions = true;
        statusLabel.setText(local ? "Listing local versions…" : "Listing versions…");
        versionComboBox.getItems().clear();

        List<String> classifierCandidates = browseClassifiers();

        if (local) {
            Task<List<String>> localTask = new Task<>() {
                @Override
                protected List<String> call() {
                    // Offer ONLY versions whose cached ~/.m2 classifiers intersect this flow's
                    // candidates — parity with the Nexus branch below, which never surfaces a
                    // version with no compatible variant (IKE-Network/ike-issues#882, #932).
                    // probeSize still gates the final selection either way.
                    return new LocalVersionResolver(M2_REPOSITORY_ROOT).compatibleVersions(coordinates, classifierCandidates);
                }
            };
            localTask.setOnSucceeded(event -> {
                listingVersions = false;
                List<String> versions = versionsIncludingPreferred(localTask.getValue(), preferredVersion);
                versionComboBox.getItems().setAll(versions);
                if (versions.isEmpty()) {
                    statusLabel.setText("No " + flow + "-compatible version cached under ~/.m2 for this artifact.");
                    return;
                }
                statusLabel.setText(versions.size() + " compatible local version(s) found.");
                versionComboBox.setValue(preferredVersion.filter(versions::contains).orElse(versions.getLast()));
            });
            localTask.setOnFailed(event -> {
                listingVersions = false;
                statusLabel.setText("Failed to list local versions: " + localTask.getException().getMessage());
            });
            TinkExecutor.threadPool().submit(localTask);
            return;
        }

        boolean nexus = NexusSearchClient.searchUri(repositoryUrl, "x").isPresent();
        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() throws Exception {
                if (nexus) {
                    // Offer ONLY versions that actually publish a compatible classified zip for
                    // this flow — from the live asset index, NOT maven-metadata.xml (which lists
                    // SOLOR's POM-only "1.0.0-SNAPSHOT" while omitting its real "20250827" zips
                    // entirely). A version with no usable variant must never appear in the picker,
                    // per IKE-Network/ike-issues#882.
                    HttpClient httpClient = HttpClient.newHttpClient();
                    return NexusSearchClient.compatibleVersions(httpClient, repositoryUrl, coordinates,
                            classifierCandidates, credentials.orElse(null));
                }
                // A non-Nexus repository (e.g. Maven Central) has no asset-index search — fall
                // back to maven-metadata.xml, unfiltered; probeSize still gates each selection.
                RemoteVersionResolver resolver = new RemoteVersionResolver();
                return credentials.isPresent()
                        ? resolver.remoteVersionListing(repositoryUrl, coordinates, credentials.get()).versions()
                        : resolver.remoteVersionListing(repositoryUrl, coordinates).versions();
            }
        };
        task.setOnSucceeded(event -> {
            listingVersions = false;
            // preferredVersion (a browse-search pick) is already asset-backed, so include it even
            // if it somehow isn't in the list — but for a Nexus repo it will be, since both come
            // from the same asset index.
            List<String> versions = versionsIncludingPreferred(task.getValue(), preferredVersion);
            versionComboBox.getItems().setAll(versions);
            if (versions.isEmpty()) {
                statusLabel.setText(nexus
                        ? "No " + flow + "-compatible version published for this artifact."
                        : "No published version metadata found for this artifact — type a known version below if you have one.");
                return;
            }
            statusLabel.setText(versions.size() + (nexus ? " compatible version(s) found." : " version(s) found."));
            versionComboBox.setValue(preferredVersion.filter(versions::contains).orElse(versions.getLast()));
        });
        task.setOnFailed(event -> {
            listingVersions = false;
            statusLabel.setText("Failed to list versions: " + task.getException().getMessage());
        });
        TinkExecutor.threadPool().submit(task);
    }

    /**
     * {@code versions} with {@code preferredVersion} guaranteed present — prepended if a browse
     * search found it but the artifact's {@code maven-metadata.xml} doesn't list it (which
     * happens: SOLOR's metadata lists only {@code 1.0.0-SNAPSHOT}, omitting the real
     * {@code 20250827} etc. that its downloadable zips actually live at). The search queries
     * Nexus's live asset index, so a version it reports is real and downloadable regardless of
     * what metadata says; surfacing it here is what lets the user actually select it. Preserves
     * {@code versions}'s order otherwise.
     *
     * @param versions the versions from {@code maven-metadata.xml}
     * @param preferredVersion the exact version a browse search reported, if any
     * @return {@code versions} with {@code preferredVersion} included
     */
    private static List<String> versionsIncludingPreferred(List<String> versions, Optional<String> preferredVersion) {
        String preferred = preferredVersion.filter(value -> !value.isBlank()).orElse(null);
        if (preferred == null || versions.contains(preferred)) {
            return List.copyOf(versions);
        }
        List<String> combined = new ArrayList<>();
        combined.add(preferred);
        combined.addAll(versions);
        return List.copyOf(combined);
    }

    /**
     * Whether {@code version} is a Maven {@code -SNAPSHOT} version — published under a repeated,
     * unresolved directory name ({@code "1.0.0-SNAPSHOT"}), but with each individual deploy's
     * files (and, per {@link NexusSearchClient#componentAssets}, Nexus's own component record)
     * actually named with a concrete, uniquely-timestamped file version instead. Downloading a
     * snapshot needs that resolved file version, not the literal {@code -SNAPSHOT} string — see
     * {@link #resolvedFileVersion}.
     *
     * @param version the version to check
     * @return {@code true} if {@code version} ends with {@code -SNAPSHOT}
     */
    private static boolean isSnapshotVersion(String version) {
        return version != null && version.endsWith("-SNAPSHOT");
    }

    /**
     * The result of resolving a version's real, published assets — for testing snapshots,
     * {@link #fileVersion} may differ from the version originally probed. {@link #assets} is the
     * same shape {@link NexusSearchClient#componentAssets} returns for a network repository
     * (classifiers with size/checksum, plus the POM's own asset info); for
     * {@link #LOCAL_REPOSITORY_SENTINEL}, classifiers are wrapped the same way but with empty
     * size/checksum/POM, since local size is read directly off disk instead
     * (see {@link #reportLocalSize}) and there's no POM to fetch over a network that isn't used.
     */
    private record ClassifierLookup(String fileVersion, NexusSearchClient.ComponentAssets assets) {
    }

    /**
     * How an already-existing {@code ~/Solor} destination's current state compares to what it
     * should be — driving {@link #freshnessLabel}. {@link #UNCHANGED}/{@link #MODIFIED} are only
     * ever reached once an actual comparison succeeded (a content-drift check for
     * {@link ProviderArtifactQualifier.Flow#SA}/{@link ProviderArtifactQualifier.Flow#ROCKS}, or a
     * direct checksum comparison for {@link ProviderArtifactQualifier.Flow#PB}); {@link #UNKNOWN}
     * covers every case where there simply isn't enough information to say either way (no
     * repository-reported checksum, no recorded local checksum, or the on-disk check itself failed).
     */
    private enum ExistingArtifactFreshness { UNCHANGED, MODIFIED, UNKNOWN }

    /**
     * Resolves which classifier actually carries {@code version} for {@link #flow}, from real,
     * authoritative data — {@link NexusSearchClient#componentAssets} (the exact asset listing
     * Nexus publishes for this groupId:artifactId:version) for a network repository, or
     * {@link LocalVersionResolver#availableClassifiers} (a plain directory listing) for
     * {@link #LOCAL_REPOSITORY_SENTINEL} — intersected against
     * {@link ProviderArtifactQualifier#classifierCandidates} to pick the best real match. This
     * is deliberately not a blind "see what's published and grab it": the candidate list is
     * still what decides which of several real variants to prefer (e.g. {@code reasoned-pb}
     * over {@code unreasoned-pb}) when more than one exists.
     *
     * <p>{@code version} here is always a concrete, published component version — never a bare
     * {@code -SNAPSHOT} placeholder unless the user typed one — because {@link #listVersions(Optional)}
     * selects the exact, asset-backed version a browse search reported ({@link NexusSearchClient.SearchMatch}),
     * injecting it even when {@code maven-metadata.xml} omits it. Nexus's component search by that
     * exact version returns its classified zips directly, for every version shape these artifacts
     * use (date-based releases, resolved snapshot builds, ordinary releases — all confirmed against
     * the live repository, 2026-07-22). The chosen classifier's own asset carries the build to
     * download in {@link NexusSearchClient.AssetInfo#resolvedVersion}, kept in
     * {@link #resolvedFileVersion} — normally identical to {@code version}. This is why the
     * SOLOR bait-and-switch (IKE-Network/ike-issues#882) is gone: metadata's incompleteness no
     * longer decides which version is probed.
     *
     * <p>When no real classifier intersects {@link #flow}'s candidates at all — including when a
     * user manually selects a genuinely empty version like SOLOR's POM-only {@code 1.0.0-SNAPSHOT} —
     * this version has no compatible variant, so it must not be offered as a downloadable option:
     * {@link #downloadButton} stays disabled and {@link #statusLabel} names what was actually
     * published versus what the flow looked for.
     */
    private void probeSize(String version) {
        resolvedClassifier = null;
        resolvedFileVersion = null;
        resolvedZipSha256 = Optional.empty();
        resolvedPom = Optional.empty();
        resolvedFreshness = ExistingArtifactFreshness.UNKNOWN;
        sizeLabel.setText("Checking size…");
        clearArtifactStatusLabels();
        ArtifactCoordinates coordinates = currentCoordinates();
        String repositoryUrl = repositoryUrlComboBox.getValue();
        boolean local = isLocal(repositoryUrl);
        Optional<Credentials> credentials = enteredCredentials();

        Task<ClassifierLookup> task = new Task<>() {
            @Override
            protected ClassifierLookup call() throws Exception {
                if (local) {
                    LocalVersionResolver resolver = new LocalVersionResolver(M2_REPOSITORY_ROOT);
                    String fileVersion = isSnapshotVersion(version)
                            ? resolver.resolveSnapshotFileVersion(coordinates, version)
                                    .orElseThrow(() -> new IOException("No cached snapshot build found for " + version))
                            : version;
                    Map<String, NexusSearchClient.AssetInfo> classifiers = resolver.availableClassifiers(coordinates, version).stream()
                            .collect(Collectors.toMap(classifier -> classifier,
                                    classifier -> new NexusSearchClient.AssetInfo(OptionalLong.empty(), Optional.empty(), Optional.of(fileVersion))));
                    return new ClassifierLookup(fileVersion, new NexusSearchClient.ComponentAssets(classifiers, Optional.empty()));
                }
                // A component search by the EXACT version resolves its classified zips directly —
                // confirmed against the live repository (2026-07-22) for every version shape these
                // artifacts actually use: plain date-based releases (SOLOR "20250827"), resolved
                // snapshot builds (rxnorm "2024-04-10+1.0.0-20260714.140246-2"), and ordinary
                // releases alike. The version reaching here is always a concrete published version
                // — a browse-search result carries the exact version its matching asset lives at
                // (see NexusSearchClient.SearchMatch), which listVersions selects directly rather
                // than trusting maven-metadata.xml, whose version list can be incomplete (SOLOR's
                // lists only a POM-only "1.0.0-SNAPSHOT", omitting the real "20250827" zips
                // entirely — the bait-and-switch of IKE-Network/ike-issues#882).
                HttpClient httpClient = HttpClient.newHttpClient();
                NexusSearchClient.ComponentAssets assets = credentials.isPresent()
                        ? NexusSearchClient.componentAssets(httpClient, repositoryUrl, coordinates, version, credentials.get())
                        : NexusSearchClient.componentAssets(httpClient, repositoryUrl, coordinates, version);
                return new ClassifierLookup(version, assets);
            }
        };
        task.setOnSucceeded(event -> {
            ClassifierLookup lookup = task.getValue();
            // Wildcards resolve here and only here: against the classifiers this exact version
            // actually publishes, so a pattern can never widen into a request for something the
            // repository doesn't have (ikmdev/komet-desktop#117).
            ClassifierSelection selection = classifierSelection();
            List<String> matched = selection.resolve(lookup.assets().classifiers().keySet());
            Optional<String> best = matched.stream().findFirst();
            if (best.isEmpty()) {
                sizeLabel.setText("Not available");
                // Names what was actually found, not just what wasn't — an empty classifier set
                // (the component resolved, but Nexus lists no classified zips under it) is a very
                // different situation from a non-empty set that simply doesn't intersect this
                // flow's candidates, and the bare "no compatible variant" wording can't tell them
                // apart.
                Set<String> published = lookup.assets().classifiers().keySet();
                statusLabel.setText(published.isEmpty()
                        ? "No classified assets found for " + coordinates.artifactId() + " " + version
                                + " — nothing to offer for the " + flow + " flow."
                        : "No " + flow + "-compatible variant for " + coordinates.artifactId() + " " + version
                                + ". Published: " + String.join(", ", published)
                                + ". Looked for: " + selection.displayText() + ".");
                return;
            }
            resolvedClassifier = best.get();
            if (matched.size() > 1) {
                // Several classifiers satisfy the selection — say which won and what else matched,
                // rather than silently picking one and leaving the user to wonder what they got.
                statusLabel.setText("Classifier " + resolvedClassifier + " selected; also matched: "
                        + String.join(", ", matched.subList(1, matched.size())) + ".");
            }
            NexusSearchClient.AssetInfo zipAsset = lookup.assets().classifiers().get(resolvedClassifier);
            // The download's filename must embed the asset's own resolved build (a timestamped
            // value for a snapshot), not the "1.0.0-SNAPSHOT" component version — fall back to the
            // probed version only if Nexus somehow reported no maven2.version.
            resolvedFileVersion = zipAsset.resolvedVersion().orElse(lookup.fileVersion());
            resolvedZipSha256 = zipAsset.sha256();
            resolvedPom = lookup.assets().pom();
            if (local) {
                reportLocalSize(coordinates, MavenDataStoreDownloadTask.directoryVersion(version),
                        resolvedFileVersion, resolvedClassifier);
            } else {
                OptionalLong size = zipAsset.size();
                sizeLabel.setText((size.isPresent() ? formatSize(size.getAsLong()) : "size unknown") + " (" + resolvedClassifier + ")");
                downloadButton.setDisable(false);
            }
            refreshExistingArtifactStatus(resolveSolorDestination(coordinates.artifactId() + "-" + resolvedFileVersion));
        });
        task.setOnFailed(event -> {
            sizeLabel.setText("Size unknown");
            statusLabel.setText("Failed to check available classifiers: " + task.getException().getMessage());
        });
        TinkExecutor.threadPool().submit(task);
    }

    /**
     * Reports {@code classifier}'s file size directly from disk — no network needed, since
     * {@link #LOCAL_REPOSITORY_SENTINEL} browsing already confirmed the file exists.
     */
    private void reportLocalSize(ArtifactCoordinates coordinates, String directoryVersion, String fileVersion, String classifier) {
        Path cacheFile = MavenDataStoreDownloadTask.localRepositoryPath(M2_REPOSITORY_ROOT, coordinates, directoryVersion, fileVersion, classifier);
        try {
            sizeLabel.setText(formatSize(Files.size(cacheFile)) + " (" + classifier + ")");
        } catch (IOException e) {
            sizeLabel.setText("(" + classifier + ")");
        }
        downloadButton.setDisable(false);
    }

    /**
     * Updates {@link #existingArtifactLabel} immediately — a plain filesystem check, cheap — and,
     * only if something is actually there to compare against, kicks off freshness verification
     * ({@link #describeExistingDestinationFreshness}) off the JavaFX Application Thread to update
     * {@link #freshnessLabel} once it completes. The freshness check may hash an entire unpacked
     * directory's on-disk content, real time for a large store, so it must never run synchronously
     * on the FX thread the way the rest of {@link #probeSize}'s quick, purely-local checks do.
     * Caches the result in {@link #resolvedFreshness} so {@link #resolveDownloadArtifactName}'s
     * confirmation dialog can reuse it verbatim instead of recomputing synchronously on the FX
     * thread when Download is clicked.
     *
     * @param destination the prospective {@code ~/Solor} destination just resolved by {@link #probeSize}
     */
    private void refreshExistingArtifactStatus(Path destination) {
        boolean exists = destinationAlreadyExists(destination);
        existingArtifactLabel.setManaged(true);
        existingArtifactLabel.setVisible(true);
        existingArtifactLabel.setText(exists ? "Knowledge Artifact: Already Exists" : "Knowledge Artifact: Ready for Download");
        existingArtifactLabel.setStyle(exists ? "-fx-text-fill: #806600;" : "-fx-text-fill: #1a7a1a;");

        if (!exists) {
            resolvedFreshness = ExistingArtifactFreshness.UNKNOWN;
            freshnessLabel.setManaged(false);
            freshnessLabel.setVisible(false);
            return;
        }

        freshnessLabel.setManaged(true);
        freshnessLabel.setVisible(true);
        freshnessLabel.setText("Local Copy: Checking…");
        freshnessLabel.setStyle("-fx-text-fill: gray;");

        Task<ExistingArtifactFreshness> freshnessTask = new Task<>() {
            @Override
            protected ExistingArtifactFreshness call() {
                return describeExistingDestinationFreshness(destination);
            }
        };
        freshnessTask.setOnSucceeded(event -> {
            resolvedFreshness = freshnessTask.getValue();
            applyFreshnessLabel(resolvedFreshness);
        });
        TinkExecutor.threadPool().submit(freshnessTask);
    }

    /**
     * Blanks every field that identifies one specific artifact — Group Id, Artifact Id, Version
     * — along with its resolved state and the Download button, so nothing on screen describes or
     * offers an artifact the user hasn't chosen. Clearing {@link #versionComboBox}'s value fires
     * its own {@code valueProperty} listener ({@link #initialize}), which already handles a blank
     * value by clearing {@link #sizeLabel} rather than probing.
     */
    private void clearSelectedArtifactFields() {
        groupIdField.clear();
        artifactIdField.clear();
        versionComboBox.getItems().clear();
        versionComboBox.setValue(null);
        resolvedClassifier = null;
        resolvedFileVersion = null;
        resolvedZipSha256 = Optional.empty();
        resolvedPom = Optional.empty();
        resolvedFreshness = ExistingArtifactFreshness.UNKNOWN;
        downloadButton.setDisable(true);
        sizeLabel.setText("");
        clearArtifactStatusLabels();
    }

    /** Hides both artifact-status lines — they only ever describe one specific probed version. */
    private void clearArtifactStatusLabels() {
        existingArtifactLabel.setManaged(false);
        existingArtifactLabel.setVisible(false);
        freshnessLabel.setManaged(false);
        freshnessLabel.setVisible(false);
    }

    private void applyFreshnessLabel(ExistingArtifactFreshness freshness) {
        freshnessLabel.setText(switch (freshness) {
            case UNCHANGED -> "Local Copy: Unchanged since extraction ✓";
            case MODIFIED -> "Local Copy: Modified since extraction ⚠";
            case UNKNOWN -> "Local Copy: Freshness unknown";
        });
        freshnessLabel.setStyle(switch (freshness) {
            case UNCHANGED -> "-fx-text-fill: green;";
            case MODIFIED -> "-fx-text-fill: red;";
            case UNKNOWN -> "-fx-text-fill: gray;";
        });
    }

    /** A full sentence version of {@link #applyFreshnessLabel}'s text, for the overwrite confirmation dialog. */
    private static String freshnessSummary(ExistingArtifactFreshness freshness) {
        return switch (freshness) {
            case UNCHANGED -> "The local copy is unchanged since extraction.";
            case MODIFIED -> "The local copy has changed since extraction — downloading now will replace it with a different version.";
            case UNKNOWN -> "The local copy's freshness could not be verified.";
        };
    }

    private ArtifactCoordinates currentCoordinates() {
        return new ArtifactCoordinates(groupIdField.getText().strip(), artifactIdField.getText().strip());
    }

    /**
     * The {@code ~/Solor} path a given artifact name would materialize to for {@link #flow} —
     * a directory for {@link ProviderArtifactQualifier.Flow#SA}/{@link ProviderArtifactQualifier.Flow#ROCKS},
     * a {@code -pb.zip}-suffixed file for {@link ProviderArtifactQualifier.Flow#PB}. Shared between
     * {@link #probeSize} (to check whether it already exists) and {@link #downloadButtonPressed}
     * (the actual materialization target), so both always agree on exactly the same path.
     *
     * @param artifactName the artifact name (e.g. {@code artifactId + "-" + fileVersion}), without
     *         any flow-specific suffix
     * @return the prospective {@code ~/Solor} destination path
     */
    private Path resolveSolorDestination(String artifactName) {
        Path solorRoot = new File(System.getProperty("user.home"), "Solor").toPath();
        return switch (flow) {
            case SA, ROCKS -> solorRoot.resolve(artifactName);
            case PB -> solorRoot.resolve(artifactName + "-pb.zip");
        };
    }

    /**
     * Whether {@code destination} already holds a previously-materialized result — a non-empty
     * directory for {@link ProviderArtifactQualifier.Flow#SA}/{@link ProviderArtifactQualifier.Flow#ROCKS},
     * a regular file for {@link ProviderArtifactQualifier.Flow#PB}.
     */
    private boolean destinationAlreadyExists(Path destination) {
        return switch (flow) {
            case SA, ROCKS -> {
                if (!Files.isDirectory(destination)) {
                    yield false;
                }
                try (var entries = Files.list(destination)) {
                    yield entries.findAny().isPresent();
                } catch (IOException e) {
                    yield false;
                }
            }
            case PB -> Files.isRegularFile(destination);
        };
    }

    /**
     * The SHA-256 already materialized at {@code destination} represents, if determinable —
     * hashed directly for {@link ProviderArtifactQualifier.Flow#PB} (the destination file <em>is</em>
     * the zip, placed as-is), or read back from {@link MavenDataStoreDownloadTask
     * #existingUnpackedSourceSha256}'s marker for {@link ProviderArtifactQualifier.Flow#SA}/
     * {@link ProviderArtifactQualifier.Flow#ROCKS} (an unpacked directory can't be hashed back
     * against the source zip directly). Empty if {@code destination} doesn't exist, or (for an
     * unpack destination) predates this marker existing.
     */
    private Optional<String> existingDestinationSha256(Path destination) {
        return switch (flow) {
            case SA, ROCKS -> MavenDataStoreDownloadTask.existingUnpackedSourceSha256(destination);
            case PB -> {
                try {
                    yield Optional.of(MavenDataStoreDownloadTask.sha256Hex(destination));
                } catch (IOException e) {
                    yield Optional.empty();
                }
            }
        };
    }

    /**
     * How {@code destination} — the path this exact version would materialize to, already
     * confirmed by the caller ({@link #refreshExistingArtifactStatus}) to exist — compares to
     * what it should be, per IKE-Network/ike-issues#882 ("does it just get overwritten? ...
     * some type of indicator as to if the item has changed since it was downloaded").
     *
     * <p>For {@link ProviderArtifactQualifier.Flow#SA}/{@link ProviderArtifactQualifier.Flow#ROCKS},
     * an on-disk content-drift check ({@link #unpackedContentDriftStatus}) is tried first and, if
     * it yields an answer, wins outright — independently of the source-zip freshness check below,
     * since a directory that's been locally edited, partially deleted, or corrupted needs
     * reporting regardless of whether the upstream repository has also changed (a source-zip
     * match alone only proves the <em>repository</em> hasn't changed, not that the
     * <em>extracted files</em> still match what was extracted).
     *
     * <p>Otherwise, falls back to comparing {@link #resolvedZipSha256} (what the repository
     * reports right now) against what's actually already on disk ({@link #existingDestinationSha256}).
     *
     * @param destination the prospective {@code ~/Solor} destination for the currently-selected
     *         version ({@link #resolveSolorDestination})
     * @return the freshness verdict
     */
    private ExistingArtifactFreshness describeExistingDestinationFreshness(Path destination) {
        if (flow != ProviderArtifactQualifier.Flow.PB) {
            Optional<ExistingArtifactFreshness> driftStatus = unpackedContentDriftStatus(destination);
            if (driftStatus.isPresent()) {
                return driftStatus.get();
            }
        }
        Optional<String> existingSha256 = existingDestinationSha256(destination);
        if (resolvedZipSha256.isEmpty() || existingSha256.isEmpty()) {
            return ExistingArtifactFreshness.UNKNOWN;
        }
        return existingSha256.get().equalsIgnoreCase(resolvedZipSha256.get())
                ? ExistingArtifactFreshness.UNCHANGED
                : ExistingArtifactFreshness.MODIFIED;
    }

    /**
     * Checks whether an already-{@link MavenDataStoreDownloadTask#unpackTask unpack}ped
     * directory's current on-disk content still matches what was actually extracted there —
     * comparing a freshly recomputed {@link MavenDataStoreDownloadTask#currentUnpackedContentSha256}
     * against the {@link MavenDataStoreDownloadTask#existingUnpackedContentSha256} marker written
     * at extraction time. This is a distinct question from "has the source repository changed":
     * a directory can drift locally (a user manually edits or deletes a file inside it, a copy
     * gets truncated by a full disk, bit rot) with the upstream repository never having changed
     * at all — the source-zip checksum alone would keep reporting "unchanged" the whole time.
     *
     * @param destination an unpacked {@code -sa}/{@code -rkb} destination directory that
     *         {@link #destinationAlreadyExists} has already confirmed exists
     * @return a verdict if the on-disk content could be compared (matches, or doesn't, or the
     *         comparison itself failed), or empty if there's nothing recorded to compare against
     *         at all, e.g. a directory unpacked before this check existed — which isn't itself
     *         evidence of drift, so the caller should fall back to the source-zip check instead
     */
    private Optional<ExistingArtifactFreshness> unpackedContentDriftStatus(Path destination) {
        Optional<String> recordedContentSha256 = MavenDataStoreDownloadTask.existingUnpackedContentSha256(destination);
        if (recordedContentSha256.isEmpty()) {
            return Optional.empty();
        }
        try {
            String currentContentSha256 = MavenDataStoreDownloadTask.currentUnpackedContentSha256(destination);
            return Optional.of(currentContentSha256.equalsIgnoreCase(recordedContentSha256.get())
                    ? ExistingArtifactFreshness.UNCHANGED
                    : ExistingArtifactFreshness.MODIFIED);
        } catch (IOException e) {
            return Optional.of(ExistingArtifactFreshness.UNKNOWN);
        }
    }

    /**
     * Resolves which artifact name to actually download under, giving the user a real choice
     * when {@code proposedArtifactName} would already overwrite something in {@code ~/Solor} —
     * the "option to overwrite ... or to uniquely name the resulting download" IKE-Network/ike-issues#882
     * asked for, standing in for silently replacing whatever's already there. Skipped entirely
     * (no dialog at all) when nothing would be overwritten, which is the common case.
     *
     * @param proposedArtifactName the artifact name {@link #probeSize} already resolved
     *         ({@link #resolveSolorDestination}'s input) — unchanged unless the user picks
     *         "Save As…"
     * @return the artifact name to actually use, or empty if the user cancelled rather than
     *         overwrite or rename
     */
    private Optional<String> resolveDownloadArtifactName(String proposedArtifactName) {
        Path proposedDestination = resolveSolorDestination(proposedArtifactName);
        if (!destinationAlreadyExists(proposedDestination)) {
            return Optional.of(proposedArtifactName);
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Already in ~/Solor");
        alert.setHeaderText("Already in ~/Solor");
        // Reuses the verdict probeSize already computed off the FX thread (resolvedFreshness)
        // rather than calling describeExistingDestinationFreshness again here — recomputing it
        // synchronously on this button-press handler would re-hash a whole unpacked directory's
        // on-disk content (unpackedContentDriftStatus) and freeze the UI while it did.
        alert.setContentText(proposedDestination + " already exists.\n" + freshnessSummary(resolvedFreshness));
        ButtonType overwriteButtonType = new ButtonType("Overwrite");
        ButtonType saveAsButtonType = new ButtonType("Save As…");
        alert.getButtonTypes().setAll(overwriteButtonType, saveAsButtonType, ButtonType.CANCEL);
        alert.initOwner(stage);
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return Optional.empty();
        }
        if (choice.get() == overwriteButtonType) {
            return Optional.of(proposedArtifactName);
        }
        return promptForSaveAsName(proposedArtifactName);
    }

    /**
     * Prompts for a replacement artifact name, pre-filled with the first name
     * ({@link #firstAvailableArtifactName}) that doesn't already collide, and keeps re-prompting
     * (with a warning) until the user either enters a genuinely free name or cancels outright.
     * The {@code -pb.zip}/plain-directory suffixing itself ({@link #resolveSolorDestination}) is
     * never exposed for editing — only the base name is — so a renamed PB download still lands
     * in the shape {@code NewController.isValidDataLocation} requires.
     *
     * @param proposedArtifactName the artifact name that turned out to already exist
     * @return the chosen, confirmed-free artifact name, or empty if the user cancelled
     */
    private Optional<String> promptForSaveAsName(String proposedArtifactName) {
        String suggestion = firstAvailableArtifactName(proposedArtifactName);
        while (true) {
            TextInputDialog dialog = new TextInputDialog(suggestion);
            dialog.setTitle("Save As");
            dialog.setHeaderText(flow == ProviderArtifactQualifier.Flow.PB
                    ? "Choose a different name — saved as ~/Solor/<name>-pb.zip"
                    : "Choose a different name — saved as ~/Solor/<name>");
            dialog.setContentText("Name:");
            dialog.initOwner(stage);
            Optional<String> entered = dialog.showAndWait();
            if (entered.isEmpty()) {
                return Optional.empty();
            }
            String candidateName = entered.get().strip();
            if (candidateName.isEmpty()) {
                continue;
            }
            if (candidateName.contains("/") || candidateName.contains(File.separator)) {
                showWarning("\"" + candidateName + "\" can't contain a path separator — pick a plain name.");
                suggestion = candidateName;
                continue;
            }
            if (!destinationAlreadyExists(resolveSolorDestination(candidateName))) {
                return Optional.of(candidateName);
            }
            showWarning(resolveSolorDestination(candidateName) + " already exists too — pick another name.");
            suggestion = candidateName;
        }
    }

    /**
     * The first {@code <baseName>-2}, {@code <baseName>-3}, … name that doesn't already collide
     * in {@code ~/Solor} — the default suggestion {@link #promptForSaveAsName} pre-fills, so most
     * users can just accept it rather than invent a name themselves.
     */
    private String firstAvailableArtifactName(String baseName) {
        String candidate = baseName;
        int suffix = 2;
        while (destinationAlreadyExists(resolveSolorDestination(candidate))) {
            candidate = baseName + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    private void showWarning(String message) {
        Alert warning = new Alert(Alert.AlertType.WARNING, message);
        warning.setTitle("Name still taken");
        warning.initOwner(stage);
        warning.showAndWait();
    }

    @FXML
    private void downloadButtonPressed() {
        String selectedVersion = versionComboBox.getValue();
        if (selectedVersion == null || selectedVersion.isBlank() || resolvedClassifier == null || resolvedFileVersion == null) {
            return;
        }
        // A remote component search answers with the resolved snapshot build, which is the version
        // embedded in the FILENAME — the directory is the base -SNAPSHOT (ikmdev/komet-desktop#116).
        String directoryVersion = MavenDataStoreDownloadTask.directoryVersion(selectedVersion.strip());
        String classifier = resolvedClassifier;
        String fileVersion = resolvedFileVersion;
        String repositoryUrl = repositoryUrlComboBox.getValue();
        boolean local = isLocal(repositoryUrl);
        ArtifactCoordinates coordinates = currentCoordinates();
        Optional<Credentials> credentials = enteredCredentials();

        // Embeds fileVersion, not the (possibly literal "-SNAPSHOT") selected version — for a
        // snapshot this is the concrete, uniquely-timestamped build actually being downloaded
        // (e.g. "example-plugin-1.0.0-20260714.135548-4"), so distinct test pulls of the same
        // -SNAPSHOT line land as distinct ~/Solor entries instead of one overwriting the other.
        String proposedArtifactName = coordinates.artifactId() + "-" + fileVersion;
        Optional<String> resolvedArtifactName = resolveDownloadArtifactName(proposedArtifactName);
        if (resolvedArtifactName.isEmpty()) {
            statusLabel.setText("Download cancelled — kept the existing copy at " + resolveSolorDestination(proposedArtifactName));
            return;
        }
        String artifactName = resolvedArtifactName.get();
        Path destination = resolveSolorDestination(artifactName);
        Path cacheFile = MavenDataStoreDownloadTask.localRepositoryPath(M2_REPOSITORY_ROOT, coordinates, directoryVersion, fileVersion, classifier);

        if (local) {
            activeTask = switch (flow) {
                case SA, ROCKS -> MavenDataStoreDownloadTask.localUnpackTask(cacheFile, destination);
                case PB -> MavenDataStoreDownloadTask.localPlaceAsFileTask(cacheFile, destination);
            };
        } else {
            URI downloadUri = MavenDataStoreDownloadTask.downloadUri(repositoryUrl, coordinates, directoryVersion, fileVersion, classifier);
            HttpClient httpClient = HttpClient.newHttpClient();
            // The POM is fetched/cached/verified alongside the zip (real Maven tooling always
            // does), from the same asset lookup probeSize already resolved resolvedZipSha256
            // from — no extra request needed to know what to fetch or what to verify it against.
            Optional<MavenDataStoreDownloadTask.AssetVerification.PomFetch> pomFetch = resolvedPom.map(pom ->
                    new MavenDataStoreDownloadTask.AssetVerification.PomFetch(
                            MavenDataStoreDownloadTask.pomUri(repositoryUrl, coordinates, directoryVersion, fileVersion),
                            MavenDataStoreDownloadTask.localPomPath(M2_REPOSITORY_ROOT, coordinates, directoryVersion, fileVersion),
                            pom.sha256()));
            MavenDataStoreDownloadTask.AssetVerification verification =
                    new MavenDataStoreDownloadTask.AssetVerification(resolvedZipSha256, pomFetch);
            activeTask = switch (flow) {
                case SA, ROCKS -> credentials.isPresent()
                        ? MavenDataStoreDownloadTask.unpackTask(httpClient, downloadUri, cacheFile, destination, verification, credentials.get())
                        : MavenDataStoreDownloadTask.unpackTask(httpClient, downloadUri, cacheFile, destination, verification);
                case PB -> credentials.isPresent()
                        ? MavenDataStoreDownloadTask.placeAsFileTask(httpClient, downloadUri, cacheFile, destination, verification, credentials.get())
                        : MavenDataStoreDownloadTask.placeAsFileTask(httpClient, downloadUri, cacheFile, destination, verification);
            };
        }
        String resultName = artifactName;

        beginDownloadUiState();

        // TrackingCallable allows exactly one listener ever (IllegalStateException on a
        // second addListener call). TinkExecutor's KometThreadPoolExecutor auto-wraps any
        // TrackingCallable submitted via ExecutorService.submit(Callable) in its own
        // TaskWrapper, which claims that slot itself — so a TrackingCallable submitted that
        // way can never also carry a caller-supplied listener. Wrapping it ourselves first and
        // binding directly to the wrapper's JavaFX properties (submitted as a Runnable, not a
        // Callable, so KometThreadPoolExecutor never re-wraps it) avoids the conflict.
        TaskWrapper<Path> taskWrapper = TaskWrapper.make(activeTask);
        downloadProgressBar.progressProperty().bind(taskWrapper.progressProperty());
        taskWrapper.progressProperty().addListener((observable, oldValue, newValue) ->
                etaLabel.setText(activeTask.estimateTimeRemainingString()));
        taskWrapper.messageProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                statusLabel.setText(newValue);
            }
        });
        taskWrapper.titleProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                statusLabel.setText(newValue);
            }
        });

        TinkExecutor.threadPool().execute(taskWrapper);
        CompletableFuture<Path> completableFuture = ProgressHelper.wrap(taskWrapper, TinkExecutor.threadPool());
        completableFuture.whenComplete((path, throwable) -> Platform.runLater(() -> {
            endDownloadUiState();
            downloadProgressBar.progressProperty().unbind();
            if (throwable != null) {
                handleDownloadFailure(throwable);
                return;
            }
            if (!local && rememberCredentialsCheckBox.isSelected()) {
                credentials.ifPresent(creds -> credentialStore.put(repositoryId(repositoryUrl), creds));
            }
            dialogSelectionStore.put(flow.name(), new DialogSelection(repositoryUrl));
            result = new DataUriOption(resultName, path.toUri());
            stage.close();
        }));
    }

    private void handleDownloadFailure(Throwable throwable) {
        Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                ? throwable.getCause() : throwable;
        if (cause instanceof CancellationException) {
            statusLabel.setText("Download cancelled.");
        } else {
            statusLabel.setText("Download failed: " + cause.getMessage());
        }
    }

    private void beginDownloadUiState() {
        downloadButton.setDisable(true);
        downloadProgressBar.setVisible(true);
        downloadProgressBar.setProgress(0);
        etaLabel.setVisible(true);
        cancelDownloadButton.setVisible(true);
    }

    private void endDownloadUiState() {
        downloadButton.setDisable(resolvedClassifier == null);
        downloadProgressBar.setVisible(false);
        etaLabel.setVisible(false);
        cancelDownloadButton.setVisible(false);
    }

    @FXML
    private void cancelDownloadButtonPressed() {
        if (activeTask != null) {
            activeTask.cancel();
        }
    }

    @FXML
    private void closeButtonPressed() {
        result = null;
        stage.close();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kilobytes = bytes / 1024.0;
        if (kilobytes < 1024) {
            return String.format("%.1f KB", kilobytes);
        }
        double megabytes = kilobytes / 1024.0;
        if (megabytes < 1024) {
            return String.format("%.1f MB", megabytes);
        }
        return String.format("%.1f GB", megabytes / 1024.0);
    }
}
