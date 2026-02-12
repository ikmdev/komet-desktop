package dev.ikm.komet.desktop.aboutdialog;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class AboutDialogPane extends DialogPane {

    private static final Logger LOG = LoggerFactory.getLogger(AboutDialogPane.class);

    private AboutDialogController controller;

    public AboutDialogPane() {
        init();
    }

    private void init() {
        getButtonTypes().addAll(ButtonType.OK);

        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/dev/ikm/komet/desktop/aboutdialog/AboutDialog.fxml"));
            Parent root = loader.load();
            controller = loader.getController();

            setContent(root);
        } catch (IOException e) {
            LOG.error("IOException loading FXML", e);
        }
    }

}
