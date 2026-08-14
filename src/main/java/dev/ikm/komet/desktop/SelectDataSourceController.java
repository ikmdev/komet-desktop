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
/**
 * Sample Skeleton for 'SelectDataSource.fxml' Controller Class
 */

package dev.ikm.komet.desktop;


import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;

import dev.ikm.tinkar.common.service.ServiceExclusionGroup;
import dev.ikm.tinkar.common.service.ServiceLifecycleManager;

import java.util.Map;
import org.controlsfx.control.PropertySheet;
import org.controlsfx.validation.ValidationMessage;
import org.controlsfx.validation.ValidationResult;
import org.controlsfx.validation.ValidationSupport;
import org.controlsfx.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.ikm.komet.desktop.datasource.maven.MavenDataSourceDialogController;
import dev.ikm.komet.desktop.datasource.maven.ProviderArtifactQualifier;
import dev.ikm.komet.framework.KometNode;
import dev.ikm.komet.framework.propsheet.KometPropertyEditorFactory;
import dev.ikm.komet.framework.propsheet.SheetItem;
import dev.ikm.komet.preferences.KometPreferences;
import dev.ikm.komet.preferences.Preferences;
import dev.ikm.komet.progress.ProgressNodeFactory;
import dev.ikm.tinkar.common.service.DataServiceController;
import dev.ikm.tinkar.common.service.DataServiceProperty;
import dev.ikm.tinkar.common.service.DataUriOption;
import dev.ikm.tinkar.common.util.text.NaturalOrder;
import dev.ikm.tinkar.common.validation.ValidationRecord;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SelectDataSourceController {

    private static final Logger LOG = LoggerFactory.getLogger(SelectDataSourceController.class);

    /**
     * User-preferences node that remembers the last data-source selection so the picker
     * can pre-select it on the next launch. This lives in the machine-global <em>user</em>
     * preferences ({@link Preferences#get()} → {@code getUserPreferences()}), not the
     * datastore-scoped configuration preferences used for the last author, because the
     * provider and knowledge base are chosen <em>before</em> any datastore is open.
     * See IKE-Network/ike-issues#786.
     */
    private static final String DATA_SOURCE_SELECTION_NODE = "datasource-selection";

    /** Key holding the {@code controllerName()} of the provider selected last time. */
    private static final String LAST_PROVIDER_KEY = "last-provider";

    /**
     * Prefix for the per-provider last-knowledge-base keys. The full key is
     * {@code "kb." + controllerName()}, so each provider remembers its own last
     * knowledge base independently.
     */
    private static final String LAST_KB_KEY_PREFIX = "kb.";

    /**
     * The folder-name property every folder-creating New-store controller exposes — equal, as
     * a record, to the {@code NEW_FOLDER_PROPERTY} each of {@code SpinedArrayProvider},
     * {@code MVStoreProvider}, and Rocks KB's {@code RocksProvider} declares verbatim as
     * {@code new DataServiceProperty("New folder name", false, true)} (confirmed against their
     * sources). Record equality is what lets this class find the property in
     * {@link #dataServicePropertyStringMap} without a compile dependency on providers loaded
     * through the dynamic plugin layer.
     */
    private static final DataServiceProperty NEW_FOLDER_PROPERTY =
            new DataServiceProperty("New folder name", false, true);

    /**
     * Package prefix identifying a datastore controller supplied by the gRPC plugin. Matched by
     * name, not by class, for the same reason as {@link #NEW_FOLDER_PROPERTY}: the plugin loads
     * through the dynamic plugin layer and komet-desktop must not compile against it.
     */
    private static final String GRPC_PROVIDER_PACKAGE = "dev.ikm.tinkar.provider.grpc.";

    /**
     * Lifecycle-manager property naming the winner of the {@code SEARCH_ENGINE} exclusion group,
     * read by {@code ServiceLifecycleManager.resolveGroupSelections()}.
     */
    private static final String SEARCH_ENGINE_GROUP_PROPERTY =
            "service.lifecycle.group.SEARCH_ENGINE";

    /**
     * The gRPC search controller's name as the lifecycle manager computes it — {@code
     * OuterClass.InnerClass}, which is what {@code getServiceName()} derives for a nested class.
     * A mismatch here does not fail loudly: the manager logs a warning and falls back to the
     * lowest-priority candidate, which would silently restore local Lucene indexing.
     */
    private static final String GRPC_SEARCH_CONTROLLER_NAME = "GrpcSearchService.Controller";

    /**
     * The local Lucene search controller's name, in the same {@code OuterClass.InnerClass} form.
     * Named explicitly for local datastores rather than leaving the group unset: with the gRPC
     * plugin bundled, {@code SEARCH_ENGINE} has two candidates, and an unset group falls back to
     * lowest effective priority — a comparison the two controllers would otherwise resolve by
     * discovery order.
     */
    private static final String LUCENE_SEARCH_CONTROLLER_NAME = "SearchProvider.Controller";

    /**
     * Store-type folder suffix per New-store {@code controllerName()} — the extension
     * {@link #deriveStoreFolderName} ends a proposed folder name with, so store folders read
     * as what they are. Controllers absent here (the Open controllers, and ephemeral's
     * folder-less New controller) get no folder-name proposal at all. Names confirmed
     * verbatim against each provider's {@code CONTROLLER_NAME}.
     */
    private static final Map<String, String> STORE_SUFFIX_BY_CONTROLLER = Map.of(
            "New SpinedArrayStore", "sa",
            "New MV Store", "mv",
            "New Rocks KB", "rkb");

    /**
     * A snapshot-resolved version tail: {@code -<yyyyMMdd>.<HHmmss>} followed by any number of
     * {@code -<number>} counters (the deploy build number, plus any Save-As copy counters).
     * Group 1 is everything up to and including the hyphen before the timestamp, groups 2 and
     * 3 the date and time.
     */
    private static final Pattern SNAPSHOT_TIMESTAMP_TAIL = Pattern.compile("(.*-)(\\d{8})\\.(\\d{6})(-\\d+)*");

    private File rootFolder = new File(System.getProperty("user.home"), "Solor");

    private File workingFolder = new File(System.getProperty("user.dir"), "target");

    private Map<DataServiceProperty, SimpleStringProperty> dataServicePropertyStringMap = new HashMap<>();

    private ValidationSupport validationSupport = new ValidationSupport();

    @FXML
    private GridPane inputGrid;

    @FXML
    private PropertySheet propertySheet;

    @FXML // ResourceBundle that was given to the FXMLLoader
    private ResourceBundle resources;

    @FXML // URL location of the FXML file that was given to the FXMLLoader
    private URL location;

    @FXML // fx:id="dataSourceChoiceBox"
    private ChoiceBox<DataServiceController<?>> dataSourceChoiceBox; // Value injected by FXMLLoader

    @FXML // fx:id="cancelButton"
    private Button cancelButton; // Value injected by FXMLLoader

    @FXML // fx:id="rootBorderPane"
    private BorderPane rootBorderPane; // Value injected by FXMLLoader

    @FXML // fx:id="fileListView"
    private ListView<DataUriOption> fileListView; // Value injected by FXMLLoader

    @FXML // fx:id="addFromMavenButton"
    private Button addFromMavenButton; // Value injected by FXMLLoader

    // This method is called by the FXMLLoader when initialization is complete
    @FXML
    void initialize() {
        assert dataSourceChoiceBox != null : "fx:id=\"dataSourceChoiceBox\" was not injected: check your FXML file 'SelectDataSource.fxml'.";
        assert cancelButton != null : "fx:id=\"cancelButton\" was not injected: check your FXML file 'SelectDataSource.fxml'.";

        ImageView mavenIcon = new ImageView(new Image(getClass().getResourceAsStream("oakleaf.png")));
        mavenIcon.setFitHeight(16);
        mavenIcon.setPreserveRatio(true);
        mavenIcon.setSmooth(true);
        addFromMavenButton.setGraphic(mavenIcon);

        ServiceLifecycleManager lifecycleManager = ServiceLifecycleManager.get();
    
        // Ensure services are discovered
        if (!lifecycleManager.isDiscovered()) {
            lifecycleManager.discoverServices();
        }

        // Get sorted DATA_PROVIDER services (already sorted alphabetically)
        lifecycleManager.getServicesForGroup(ServiceExclusionGroup.DATA_PROVIDER)
                .forEach(service -> dataSourceChoiceBox.getItems().add((DataServiceController<?>) service));

        dataSourceChoiceBox.getSelectionModel().selectedItemProperty().addListener(this::dataSourceChanged);

        fileListView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                okButtonPressed(null);
            }
        });

        // Selecting a changeset proposes the matching New-store folder name
        // (ikmdev/komet-desktop#120) — derived, minimal, unique, and still editable.
        fileListView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> proposeNewFolderName(newValue));

        // Grey out datastores already open in another process. The probe is
        // advisory and can go stale (TOCTOU); okButtonPressed re-checks the
        // selection, and the open-time DataStoreAlreadyOpenException path is the
        // final backstop. See ike-issues#723 / #722.
        fileListView.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(DataUriOption item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    setDisable(false);
                    return;
                }
                DataServiceController<?> controller = dataSourceChoiceBox.getValue();
                Optional<String> conflict = (controller == null)
                        ? Optional.empty()
                        : controller.openConflict(item);
                if (conflict.isPresent()) {
                    setText(item.name() + "  —  " + conflict.get());
                    setTooltip(new Tooltip(conflict.get()
                            + ".\nClose the other Komet process, or open a different datastore."));
                    setDisable(true);
                } else {
                    setText(item.name());
                    setTooltip(null);
                    setDisable(false);
                }
            }
        });

        propertySheet.setPropertyEditorFactory(new KometPropertyEditorFactory(null));

        // Pre-select the provider used last time (machine-global user preference). Fall
        // back to the first "Open Spined..." controller, then to the first controller.
        // Selecting fires dataSourceChanged(), which restores that provider's last
        // knowledge base. See IKE-Network/ike-issues#786.
        Optional<String> lastProvider = readLastProvider();
        dataSourceChoiceBox.getItems().stream()
                .filter(dataServiceController -> lastProvider
                        .map(name -> name.equals(dataServiceController.controllerName()))
                        .orElse(false))
                .findFirst()
                .or(() -> dataSourceChoiceBox.getItems().stream()
                        .filter(dataServiceController -> dataServiceController.controllerName().startsWith("Open Spined"))
                        .findFirst())
                .ifPresentOrElse(dataSourceChoiceBox.getSelectionModel()::select,
                        dataSourceChoiceBox.getSelectionModel()::selectFirst);
    }

    void dataSourceChanged(ObservableValue<? extends DataServiceController<?>> observable,
                           DataServiceController<?> oldValue,
                           DataServiceController<?> newValue) {
        saveDataServiceProperties(oldValue);

        dataServicePropertyStringMap.clear();
        fileListView.getItems().clear();
        fileListView.getItems().addAll(dataSourceChoiceBox.getValue().providerOptions());
        fileListView.getItems().sort(NaturalOrder.getObjectComparator());
        selectRememberedOrFirstKnowledgeBase(dataSourceChoiceBox.getValue());
        fileListView.requestFocus();

        Optional<ProviderArtifactQualifier.Flow> mavenFlow =
                ProviderArtifactQualifier.flowFor(dataSourceChoiceBox.getValue().controllerName());
        addFromMavenButton.setVisible(mavenFlow.isPresent());
        addFromMavenButton.setManaged(mavenFlow.isPresent());

        propertySheet.getItems().clear();
        validationSupport = new ValidationSupport();

        DataServiceController<?> dataSourceController = dataSourceChoiceBox.getValue();
        dataSourceController.providerProperties().forEachKeyValue(
                (DataServiceProperty key, String value) -> {
                    SimpleStringProperty dataServiceProperty = new SimpleStringProperty(key, key.propertyName(), value);
                    dataServicePropertyStringMap.put(key, dataServiceProperty);
                    if (key.validate()) {
                        Validator validator = new Validator<String>() {

                            @Override
                            public ValidationResult apply(Control control, String s) {
                                ValidationResult validationResult = new ValidationResult();
                                for (ValidationRecord validationRecord : dataSourceController.validate(key, s, control)) {
                                    switch (validationRecord.severity()) {
                                        case ERROR -> validationResult.add(ValidationMessage.error(
                                                (Control) validationRecord.target(), validationRecord.message()));
                                        case INFO -> validationResult.add(ValidationMessage.info(
                                                (Control) validationRecord.target(), validationRecord.message()));
                                        case OK -> validationResult.add(ValidationMessage.ok(
                                                (Control) validationRecord.target(), validationRecord.message()));
                                        case WARNING -> validationResult.add(ValidationMessage.warning(
                                                (Control) validationRecord.target(), validationRecord.message()));
                                    }
                                }
                                return validationResult;
                            }
                        };
                        if (key.hiddenText()) {
                            propertySheet.getItems().add(SheetItem.makeForPassword(dataServiceProperty, validationSupport, validator));

                        } else {
                            propertySheet.getItems().add(SheetItem.make(dataServiceProperty, validationSupport, validator, null));
                        }
                    } else {
                        if (key.hiddenText()) {
                            propertySheet.getItems().add(SheetItem.makeForPassword(dataServiceProperty));
                        } else {
                            propertySheet.getItems().add(SheetItem.make(dataServiceProperty));
                        }
                    }
                });
    }

    @FXML
    void okButtonPressed(ActionEvent event) {
        // Early warning: don't launch into a store that is already open elsewhere.
        // Re-probe the current selection (the greyed-out list state can be stale).
        DataServiceController<?> selectedController = dataSourceChoiceBox.getValue();
        DataUriOption selectedOption = fileListView.getSelectionModel().getSelectedItem();
        if (selectedController != null && selectedOption != null) {
            Optional<String> conflict = selectedController.openConflict(selectedOption);
            if (conflict.isPresent()) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("Datastore in use");
                alert.setHeaderText("This datastore is open in another process");
                alert.setContentText(selectedOption.name() + " — " + conflict.get()
                        + ".\n\nEither close the other process, or open a different datastore.");
                alert.showAndWait();
                return;
            }
        }

        // Remember this provider + knowledge base so the next launch pre-selects it.
        persistSelection(selectedController, selectedOption);

        saveDataServiceProperties(dataSourceChoiceBox.getValue());
        dataSourceChoiceBox.getValue().setDataUriOption(fileListView.getSelectionModel().getSelectedItem());
        
        // Select the service for the group BEFORE starting services
        ServiceLifecycleManager.get().selectServiceForGroup(
            ServiceExclusionGroup.DATA_PROVIDER,
            dataSourceChoiceBox.getValue().getClass()
        );

        // A remote datastore answers search over the wire, so the local Lucene indexer must not
        // also run: SearchProvider creates its index directory eagerly, leaving an empty index
        // behind for a store that is never queried locally. Both search providers belong to the
        // SEARCH_ENGINE exclusion group; naming the winner here is what excludes the other.
        //
        // Set by name rather than by class so this stays free of a compile dependency on the
        // optional plugin. When the plugin is absent the group has a single candidate and the
        // lifecycle manager auto-selects local Lucene, so this property is never consulted.
        // Both branches name a winner explicitly. Clearing the property instead would leave the
        // group unresolved, and with the plugin bundled its two candidates tie on effective
        // priority — the manager would then break the tie by discovery order.
        boolean remoteDatastore = selectedController != null
                && selectedController.getClass().getName().startsWith(GRPC_PROVIDER_PACKAGE);
        System.setProperty(SEARCH_ENGINE_GROUP_PROPERTY,
                remoteDatastore ? GRPC_SEARCH_CONTROLLER_NAME : LUCENE_SEARCH_CONTROLLER_NAME);


        TabPane progressTabPane = new TabPane();
        rootBorderPane.setCenter(progressTabPane);
        rootBorderPane.setTop(null);
        rootBorderPane.setBottom(null);
        ProgressNodeFactory progressNodeFactory = new ProgressNodeFactory();
        KometNode kometNode = progressNodeFactory.create();
        Tab progressTab = new Tab(kometNode.getTitle().getValue(), kometNode.getNode());
        progressTab.setGraphic(kometNode.getTitleNode());
        progressTabPane.getTabs().add(progressTab);

        App.state.set(AppState.SELECTED_DATA_SOURCE);
    }

    /**
     * Opens the "Add from Maven" dialog for the currently selected provider and, on a
     * successful download, adds the resulting {@link DataUriOption} to {@link #fileListView}
     * and selects it — from that point it's an ordinary local entry, indistinguishable from
     * one the user placed under {@code ~/Solor} by hand.
     *
     * <p>Re-downloading the exact same resolved build (same artifact, same version — for a
     * {@code -SNAPSHOT}, the same concrete timestamped build, not just the same {@code -SNAPSHOT}
     * line) materializes to the exact same {@code ~/Solor} path and so returns a
     * {@link DataUriOption} equal to one already in {@link #fileListView} ({@code equals()} is
     * structural — same name and URI). That must select the existing row, not add a second,
     * visually-identical one.
     *
     * @param event the triggering action event; unused
     */
    @FXML
    void addFromMavenButtonPressed(ActionEvent event) {
        DataServiceController<?> selectedController = dataSourceChoiceBox.getValue();
        if (selectedController == null) {
            return;
        }
        Optional<ProviderArtifactQualifier.Flow> flow =
                ProviderArtifactQualifier.flowFor(selectedController.controllerName());
        if (flow.isEmpty()) {
            return;
        }
        try {
            Stage owner = (Stage) addFromMavenButton.getScene().getWindow();
            Optional<DataUriOption> result = MavenDataSourceDialogController.show(owner, flow.get());
            result.ifPresent(option -> {
                if (!fileListView.getItems().contains(option)) {
                    fileListView.getItems().add(option);
                    fileListView.getItems().sort(NaturalOrder.getObjectComparator());
                }
                fileListView.getSelectionModel().select(option);
                fileListView.scrollTo(option);
            });
        } catch (IOException e) {
            LOG.error("Failed to open the Add from Maven dialog", e);
        }
    }

    /**
     * Proposes a New-store folder name derived from {@code selected} (ikmdev/komet-desktop#120)
     * — only when the current controller is a folder-creating New-store controller
     * ({@link #STORE_SUFFIX_BY_CONTROLLER}) whose {@link #NEW_FOLDER_PROPERTY} is already on
     * the property sheet. During a provider switch, {@link #dataSourceChanged} restores the
     * remembered selection <em>before</em> it populates {@link #dataServicePropertyStringMap},
     * so that restored selection deliberately proposes nothing — a folder name the user typed
     * earlier (carried in the controller's own properties) survives switching away and back.
     * Only an actual selection in the visible list — including the auto-select after a Maven
     * download ({@link #addFromMavenButtonPressed}) — overwrites the field.
     *
     * @param selected the newly selected knowledge base; {@code null} (a cleared selection)
     *         proposes nothing
     */
    private void proposeNewFolderName(DataUriOption selected) {
        if (selected == null) {
            return;
        }
        DataServiceController<?> controller = dataSourceChoiceBox.getValue();
        if (controller == null) {
            return;
        }
        String storeSuffix = STORE_SUFFIX_BY_CONTROLLER.get(controller.controllerName());
        if (storeSuffix == null) {
            return;
        }
        SimpleStringProperty folderNameProperty = dataServicePropertyStringMap.get(NEW_FOLDER_PROPERTY);
        if (folderNameProperty == null) {
            return;
        }
        folderNameProperty.set(firstAvailableFolderName(rootFolder,
                deriveStoreFolderName(selected.name(), storeSuffix)));
    }

    /**
     * Derives the proposed New-store folder name from a selected changeset name: the minimal
     * name that stays unique while preserving a snapshot's date and time, without the
     * classifier (ikmdev/komet-desktop#120). A trailing {@code .zip} is dropped (the
     * in-session entry a Maven download adds has none; disk-scanned entries do). Trailing
     * {@code -<classifier>} tails from {@link ProviderArtifactQualifier#classifierCandidates}
     * are stripped longest-first and repeatedly, so the classifier-qualified
     * {@code ...-changeset-pb} shape (ikmdev/komet-desktop#118) loses both tails. A snapshot
     * {@code -<yyyyMMdd>.<HHmmss>-<build>} tail keeps its date and time but drops the
     * counters, the timestamp's dot becoming a hyphen so the only dot left is the store-suffix
     * separator. Finally the store-type suffix is appended. Examples:
     * {@code ike-starter-set-1-20260726.155158-4-reasoned-pb} →
     * {@code ike-starter-set-1-20260726-155158.sa};
     * {@code pizzakb-1.0.0-pb.zip} → {@code pizzakb-1.0.0.sa}.
     *
     * @param selectionName the selected {@link DataUriOption#name()}
     * @param storeSuffix the store-type suffix, without the dot (e.g. {@code "sa"})
     * @return the proposed folder name
     */
    static String deriveStoreFolderName(String selectionName, String storeSuffix) {
        String zipStripped = selectionName.strip();
        if (zipStripped.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            zipStripped = zipStripped.substring(0, zipStripped.length() - ".zip".length());
        }
        List<String> classifierTails = new ArrayList<>(
                ProviderArtifactQualifier.classifierCandidates(ProviderArtifactQualifier.Flow.PB));
        classifierTails.sort(Comparator.comparingInt(String::length).reversed());
        String name = zipStripped;
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String classifier : classifierTails) {
                String tail = "-" + classifier;
                if (name.toLowerCase(Locale.ROOT).endsWith(tail)) {
                    name = name.substring(0, name.length() - tail.length());
                    stripped = true;
                    break;
                }
            }
        }
        if (name.isEmpty()) {
            // A name that was nothing but classifier tails — keep it recognizable instead.
            name = zipStripped;
        }
        Matcher timestamped = SNAPSHOT_TIMESTAMP_TAIL.matcher(name);
        if (timestamped.matches()) {
            name = timestamped.group(1) + timestamped.group(2) + "-" + timestamped.group(3);
        }
        return name + "." + storeSuffix;
    }

    /**
     * The first of {@code derivedName}, {@code <base>-2.<ext>}, {@code <base>-3.<ext>}, … that
     * doesn't already exist under {@code rootFolder} — a proposed name should validate
     * immediately, not arrive pre-flagged "directory exists". The counter goes before the
     * store-type suffix so bumped names still read as stores ({@code ...-155158-2.sa}).
     *
     * @param rootFolder the {@code ~/Solor} root
     * @param derivedName the derived folder name ({@link #deriveStoreFolderName})
     * @return the first candidate with no existing file or directory in its way
     */
    static String firstAvailableFolderName(File rootFolder, String derivedName) {
        if (!new File(rootFolder, derivedName).exists()) {
            return derivedName;
        }
        int extensionStart = derivedName.lastIndexOf('.');
        String base = extensionStart < 0 ? derivedName : derivedName.substring(0, extensionStart);
        String extension = extensionStart < 0 ? "" : derivedName.substring(extensionStart);
        int counter = 2;
        while (new File(rootFolder, base + "-" + counter + extension).exists()) {
            counter++;
        }
        return base + "-" + counter + extension;
    }

    private void saveDataServiceProperties(DataServiceController<?> dataServiceController) {
        dataServicePropertyStringMap.forEach((dataServiceProperty, simpleStringProperty) -> {
            dataServiceController.setDataServiceProperty(dataServiceProperty, simpleStringProperty.getValue());
        });
    }

    /**
     * Selects, in {@link #fileListView}, the knowledge base this provider opened last time,
     * falling back to the first item when there is no remembered selection or the remembered
     * knowledge base is no longer present (e.g. the directory was moved or deleted).
     *
     * @param controller the currently selected data-source provider; may be {@code null}
     */
    private void selectRememberedOrFirstKnowledgeBase(DataServiceController<?> controller) {
        Optional<String> lastKnowledgeBaseUri = readLastKnowledgeBaseUri(controller);
        if (lastKnowledgeBaseUri.isPresent()) {
            for (DataUriOption option : fileListView.getItems()) {
                if (lastKnowledgeBaseUri.get().equals(option.uri().toString())) {
                    fileListView.getSelectionModel().select(option);
                    fileListView.scrollTo(option);
                    return;
                }
            }
        }
        fileListView.getSelectionModel().selectFirst();
    }

    /**
     * Persists the confirmed selection — the provider and its knowledge base — to the
     * machine-global user preferences so the next launch can pre-select them. A preferences
     * failure is logged but never blocks opening the datastore.
     *
     * @param controller the confirmed data-source provider; a {@code null} controller is ignored
     * @param option     the confirmed knowledge base; if {@code null}, only the provider is stored
     */
    private static void persistSelection(DataServiceController<?> controller, DataUriOption option) {
        if (controller == null) {
            return;
        }
        try {
            KometPreferences preferences = dataSourceSelectionPreferences();
            preferences.put(LAST_PROVIDER_KEY, controller.controllerName());
            if (option != null) {
                preferences.put(lastKnowledgeBaseKey(controller), option.uri().toString());
            }
            preferences.flush();
        } catch (Exception ex) {
            LOG.warn("Could not persist last-selected data-source preference", ex);
        }
    }

    /**
     * Reads the {@code controllerName()} of the provider selected on the previous launch.
     *
     * @return the remembered provider name, or empty if none is stored or it cannot be read
     */
    private static Optional<String> readLastProvider() {
        try {
            return dataSourceSelectionPreferences().get(LAST_PROVIDER_KEY);
        } catch (Exception ex) {
            LOG.warn("Could not read last-selected data-source provider preference", ex);
            return Optional.empty();
        }
    }

    /**
     * Reads the URI of the knowledge base the given provider opened on the previous launch.
     *
     * @param controller the provider whose last knowledge base is requested; may be {@code null}
     * @return the remembered knowledge-base URI string, or empty if none is stored or it
     *         cannot be read
     */
    private static Optional<String> readLastKnowledgeBaseUri(DataServiceController<?> controller) {
        if (controller == null) {
            return Optional.empty();
        }
        try {
            return dataSourceSelectionPreferences().get(lastKnowledgeBaseKey(controller));
        } catch (Exception ex) {
            LOG.warn("Could not read last-selected knowledge base preference for provider {}",
                    controller.controllerName(), ex);
            return Optional.empty();
        }
    }

    /**
     * Returns the user-preferences node that holds the last data-source selection.
     *
     * @return the {@value #DATA_SOURCE_SELECTION_NODE} node under the user preferences root
     */
    private static KometPreferences dataSourceSelectionPreferences() {
        return Preferences.get().getUserPreferences().node(DATA_SOURCE_SELECTION_NODE);
    }

    /**
     * Builds the per-provider preference key for that provider's last knowledge base.
     *
     * @param controller the provider; must not be {@code null}
     * @return the preference key, {@code "kb." + controllerName()}
     */
    private static String lastKnowledgeBaseKey(DataServiceController<?> controller) {
        return LAST_KB_KEY_PREFIX + controller.controllerName();
    }

    /**
     * Returns the cancel button.
     */
    public Button getCancelButton() {
        return cancelButton;
    }
}
