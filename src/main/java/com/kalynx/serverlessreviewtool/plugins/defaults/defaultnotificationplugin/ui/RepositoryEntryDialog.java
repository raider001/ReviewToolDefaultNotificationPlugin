package com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.ui;

import com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.PollerConfig;
import com.kalynx.swingtheme.themedcomponents.CustomTitleBar;
import com.kalynx.swingtheme.themedcomponents.FocusCondition;
import com.kalynx.swingtheme.themedcomponents.ThemedButton;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedRootPane;
import com.kalynx.swingtheme.themedcomponents.ThemedSpinner;
import com.kalynx.swingtheme.themedcomponents.ThemedTextField;
import com.kalynx.swingtheme.theme.ThemeManager;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;

/**
 * Modal dialog for adding or editing a repository entry.
 * Collects name, location URL, and poll interval from the user.
 */
public class RepositoryEntryDialog extends JDialog {

    private final ThemedTextField nameField = new ThemedTextField(25);
    private final ThemedTextField locationField = new ThemedTextField(25);
    private final ThemedSpinner pollIntervalSpinner = new ThemedSpinner(new SpinnerNumberModel(60, 1, 86400, 1));

    private boolean confirmed = false;

    /**
     * Creates a dialog for adding a new repository.
     *
     * @param owner the parent window
     */
    public RepositoryEntryDialog(Window owner) {
        this(owner, null);
    }

    /**
     * Creates a dialog pre-populated for editing an existing repository.
     *
     * @param owner  the parent window
     * @param config the repository config to edit, or {@code null} for a new entry
     */
    public RepositoryEntryDialog(Window owner, PollerConfig config) {
        super(owner, ModalityType.APPLICATION_MODAL);
        setUndecorated(true);
        String title = config == null ? "Add Repository" : "Edit Repository";
        configureLayout(title);
        setupListeners();
        if (config != null) {
            loadConfig(config);
        }
        pack();
        setSize(480, 260);
        setLocationRelativeTo(owner);
    }

    private void configureLayout(String title) {
        ThemedPanel root = new ThemedPanel();
        root.setLayout(new MigLayout("fill, insets 0", "[grow]", "[][grow][]"));
        root.setBorder(BorderFactory.createLineBorder(
            ThemeManager.getInstance().getCurrentTheme().getBorderColor(), 1));

        root.add(new CustomTitleBar(this, title), "cell 0 0, grow, wrap");

        ThemedPanel formPanel = new ThemedPanel();
        formPanel.setLayout(new MigLayout("insets 15 20 10 20", "[][grow]", "[]10[]10[]"));
        formPanel.add(new ThemedLabel("Name:"),           "cell 0 0");
        formPanel.add(nameField,                          "cell 1 0, growx");
        formPanel.add(new ThemedLabel("Location (URL):"), "cell 0 1");
        formPanel.add(locationField,                      "cell 1 1, growx");
        formPanel.add(new ThemedLabel("Poll interval (s):"), "cell 0 2");
        formPanel.add(pollIntervalSpinner,                "cell 1 2");
        root.add(formPanel, "cell 0 1, grow, wrap");

        ThemedPanel buttonPanel = new ThemedPanel();
        buttonPanel.setLayout(new MigLayout("insets 5 20 15 20", "[grow][]10[]", "[]"));
        ThemedButton saveButton   = new ThemedButton("Save");
        ThemedButton cancelButton = new ThemedButton("Cancel");
        saveButton.addActionListener(this::onSave);
        cancelButton.addActionListener(this::dispose);
        buttonPanel.add(saveButton,   "cell 1 0, width 80!");
        buttonPanel.add(cancelButton, "cell 2 0, width 80!");
        root.add(buttonPanel, "cell 0 2, grow");

        setContentPane(root);
    }

    @Override
    protected JRootPane createRootPane() {
        return new ThemedRootPane();
    }

    private void setupListeners() {
        ((ThemedRootPane) getRootPane()).registerKeyboardAction(
            this::dispose,
            KeyStroke.getKeyStroke("ESCAPE"),
            FocusCondition.WHEN_IN_FOCUSED_WINDOW
        );
    }

    private void loadConfig(PollerConfig config) {
        nameField.setText(config.repositoryName());
        locationField.setText(config.repositoryUrl());
        pollIntervalSpinner.setValue((int) (config.pollIntervalMs() / 1000));
    }

    private void onSave() {
        if (nameField.getText().isBlank()) {
            nameField.requestFocus();
            return;
        }
        if (locationField.getText().isBlank()) {
            locationField.requestFocus();
            return;
        }
        confirmed = true;
        dispose();
    }

    /**
     * Returns whether the user confirmed the dialog by clicking Save.
     *
     * @return {@code true} if the user saved, {@code false} if cancelled
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Builds a {@link PollerConfig} from the current field values.
     * Only meaningful when {@link #isConfirmed()} returns {@code true}.
     *
     * @return the resulting config
     */
    public PollerConfig buildConfig() {
        long pollMs = ((Number) pollIntervalSpinner.getValue()).longValue() * 1000L;
        return new PollerConfig(
            nameField.getText().trim(),
            locationField.getText().trim(),
            pollMs
        );
    }
}

