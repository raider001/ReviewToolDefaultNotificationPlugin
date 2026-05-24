package com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.ui;

import com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.IndexerConfig;
import com.kalynx.swingtheme.themedcomponents.CustomTitleBar;
import com.kalynx.swingtheme.themedcomponents.FocusCondition;
import com.kalynx.swingtheme.themedcomponents.ThemedButton;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedRootPane;
import com.kalynx.swingtheme.themedcomponents.ThemedTextField;
import com.kalynx.swingtheme.theme.ThemeManager;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;

/**
 * Modal dialog for adding or editing a repository entry.
 *
 * <p>Collects the repository identifier used by the Central Indexer (e.g. {@code owner/repo})
 * and the git clone URL or filesystem path used by the review tool application.
 */
public class RepositoryEntryDialog extends JDialog {

    private final ThemedTextField nameField     = new ThemedTextField(25);
    private final ThemedTextField locationField = new ThemedTextField(25);

    private boolean confirmed = false;

    /**
     * Creates a dialog for adding a new repository entry.
     *
     * @param owner the parent window
     */
    public RepositoryEntryDialog(Window owner) {
        this(owner, null);
    }

    /**
     * Creates a dialog pre-populated for editing an existing repository entry.
     *
     * @param owner the parent window
     * @param entry the entry to edit, or {@code null} for a new entry
     */
    public RepositoryEntryDialog(Window owner, IndexerConfig.RepositoryEntry entry) {
        super(owner, ModalityType.APPLICATION_MODAL);
        setUndecorated(true);
        String title = entry == null ? "Add Repository" : "Edit Repository";
        configureLayout(title);
        setupListeners();
        if (entry != null) {
            loadEntry(entry);
        }
        pack();
        setSize(480, 220);
        setLocationRelativeTo(owner);
    }

    private void configureLayout(String title) {
        ThemedPanel root = new ThemedPanel();
        root.setLayout(new MigLayout("fill, insets 0", "[grow]", "[][grow][]"));
        root.setBorder(BorderFactory.createLineBorder(
                ThemeManager.getInstance().getCurrentTheme().getBorderColor(), 1));

        root.add(new CustomTitleBar(this, title), "cell 0 0, grow, wrap");

        ThemedPanel formPanel = new ThemedPanel();
        formPanel.setLayout(new MigLayout("insets 15 20 10 20", "[][grow]", "[]10[]"));
        formPanel.add(new ThemedLabel("Name (owner/repo):"), "cell 0 0");
        formPanel.add(nameField,                             "cell 1 0, growx");
        formPanel.add(new ThemedLabel("Location (URL):"),   "cell 0 1");
        formPanel.add(locationField,                        "cell 1 1, growx");
        root.add(formPanel, "cell 0 1, grow, wrap");

        ThemedButton saveButton   = new ThemedButton("Save");
        ThemedButton cancelButton = new ThemedButton("Cancel");
        saveButton.addActionListener(e -> onSave());
        cancelButton.addActionListener(e -> dispose());

        ThemedPanel buttonPanel = new ThemedPanel();
        buttonPanel.setLayout(new MigLayout("insets 5 20 15 20", "[grow][]10[]", "[]"));
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
                FocusCondition.WHEN_IN_FOCUSED_WINDOW);
    }

    private void loadEntry(IndexerConfig.RepositoryEntry entry) {
        nameField.setText(entry.name());
        locationField.setText(entry.location());
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
     * @return {@code true} if saved, {@code false} if cancelled
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Builds a {@link IndexerConfig.RepositoryEntry} from the current field values.
     * Only meaningful when {@link #isConfirmed()} is {@code true}.
     *
     * @return the resulting repository entry
     */
    public IndexerConfig.RepositoryEntry buildEntry() {
        return new IndexerConfig.RepositoryEntry(
                nameField.getText().trim(),
                locationField.getText().trim());
    }
}

