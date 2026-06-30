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

import java.io.File;
import java.net.URL;
import java.util.HashMap;
import java.util.Optional;
import java.util.ResourceBundle;

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

    // This method is called by the FXMLLoader when initialization is complete
    @FXML
    void initialize() {
        assert dataSourceChoiceBox != null : "fx:id=\"dataSourceChoiceBox\" was not injected: check your FXML file 'SelectDataSource.fxml'.";
        assert cancelButton != null : "fx:id=\"cancelButton\" was not injected: check your FXML file 'SelectDataSource.fxml'.";

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
