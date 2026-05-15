package com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.ui;

import com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.PollerConfig;
import com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.PollerConfigLoader;
import com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.PollerConfigSaver;
import com.kalynx.swingtheme.themedcomponents.ThemedButton;
import com.kalynx.swingtheme.themedcomponents.ThemedConfirmDialog;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedList;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedScrollPane;
import com.kalynx.swingtheme.themedcomponents.ThemedTextField;
import com.kalynx.swingtheme.themedcomponents.ThemedTitledBorder;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel for viewing and managing the repositories tracked by the default notification plugin.
 * Displays the current repository list and provides controls to add, edit, and remove entries.
 * Changes are persisted immediately to {@code repositories.json}.
 */
public class RepositoriesManagementPanel extends ThemedPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoriesManagementPanel.class);

    private final PollerConfigLoader loader;
    private final PollerConfigSaver saver;

    private final List<PollerConfig> allRepositories = new ArrayList<>();
    private final DefaultListModel<PollerConfig> listModel = new DefaultListModel<>();
    private final ThemedList<PollerConfig> repositoryList = new ThemedList<>(listModel);
    private final ThemedTextField searchField = new ThemedTextField(20);
    private final ThemedButton addButton    = new ThemedButton("Add");
    private final ThemedButton editButton   = new ThemedButton("Edit");
    private final ThemedButton removeButton = new ThemedButton("Remove");
    private final ThemedLabel statusLabel   = new ThemedLabel(" ");

    /**
     * Creates a new {@code RepositoriesManagementPanel}.
     *
     * @param loader reads the current repository configuration from disk
     * @param saver  persists configuration changes to disk
     */
    public RepositoriesManagementPanel(PollerConfigLoader loader, PollerConfigSaver saver) {
        this.loader = loader;
        this.saver = saver;
        setBorder(ThemedTitledBorder.create("Repositories"));
        configureLayout();
        setupListeners();
        loadRepositories();
    }

    private void configureLayout() {
        setLayout(new MigLayout("fill, insets 10", "[grow][]", "[]8[grow][]"));

        searchField.putClientProperty("JTextField.placeholderText", "Search repositories...");
        add(searchField, "cell 0 0 2 1, growx");

        repositoryList.setCellRenderer(new PollerConfigRenderer());
        ThemedScrollPane scrollPane = new ThemedScrollPane(repositoryList);
        add(scrollPane, "cell 0 1, grow");

        add(buildButtonPanel(), "cell 1 1, growy");

        add(statusLabel, "cell 0 2 2 1, growx");
    }

    private ThemedPanel buildButtonPanel() {
        ThemedPanel panel = new ThemedPanel();
        panel.setLayout(new MigLayout("fillx, insets 0 5 0 0", "[grow]", "[]5[]5[]push"));
        panel.add(addButton,    "growx, wrap");
        panel.add(editButton,   "growx, wrap");
        panel.add(removeButton, "growx");
        return panel;
    }

    private void setupListeners() {
        updateButtonStates();
        repositoryList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateButtonStates();
            }
        });
        addButton.addActionListener(this::onAdd);
        editButton.addActionListener(this::onEdit);
        removeButton.addActionListener(this::onRemove);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
    }

    private void loadRepositories() {
        allRepositories.clear();
        allRepositories.addAll(loader.loadConfigurations());
        applyFilter();
    }

    private void applyFilter() {
        String query = searchField.getText().trim().toLowerCase();
        PollerConfig previousSelection = repositoryList.getSelectedValue();

        listModel.clear();
        allRepositories.stream()
            .filter(c -> matchesFilter(c, query))
            .forEach(listModel::addElement);

        if (previousSelection != null) {
            repositoryList.setSelectedValue(previousSelection, true);
        }
        updateButtonStates();
    }

    private boolean matchesFilter(PollerConfig config, String query) {
        if (query.isEmpty()) {
            return true;
        }
        return config.repositoryName().toLowerCase().contains(query)
            || config.repositoryUrl().toLowerCase().contains(query);
    }

    private void onAdd() {
        RepositoryEntryDialog dialog = new RepositoryEntryDialog(getParentWindow());
        dialog.setVisible(true);
        if (dialog.isConfirmed()) {
            PollerConfig config = dialog.buildConfig();
            allRepositories.add(config);
            saveAll("Repository added.");
            applyFilter();
            repositoryList.setSelectedValue(config, true);
        }
    }

    private void onEdit() {
        PollerConfig existing = repositoryList.getSelectedValue();
        if (existing == null) {
            return;
        }
        RepositoryEntryDialog dialog = new RepositoryEntryDialog(getParentWindow(), existing);
        dialog.setVisible(true);
        if (dialog.isConfirmed()) {
            PollerConfig updated = dialog.buildConfig();
            int masterIndex = allRepositories.indexOf(existing);
            if (masterIndex >= 0) {
                allRepositories.set(masterIndex, updated);
            }
            saveAll("Repository updated.");
            applyFilter();
            repositoryList.setSelectedValue(updated, true);
        }
    }

    private void onRemove() {
        PollerConfig config = repositoryList.getSelectedValue();
        if (config == null) {
            return;
        }
        boolean confirmed = ThemedConfirmDialog.showConfirmation(
            getParentWindow(),
            "Remove Repository",
            "Remove '" + config.repositoryName() + "'?"
        );
        if (confirmed) {
            allRepositories.remove(config);
            saveAll("Repository removed.");
            applyFilter();
        }
    }

    private void saveAll(String successMessage) {
        try {
            saver.save(allRepositories);
            setStatus(successMessage);
        } catch (IOException e) {
            LOGGER.error("Failed to save repository configuration", e);
            setStatus("Error saving configuration.");
        }
    }

    private void updateButtonStates() {
        boolean hasSelection = repositoryList.getSelectedIndex() >= 0;
        editButton.setEnabled(hasSelection);
        removeButton.setEnabled(hasSelection);
    }

    private void setStatus(String message) {
        statusLabel.setText(message);
        Timer timer = new Timer(3000, _ -> statusLabel.setText(" "));
        timer.setRepeats(false);
        timer.start();
    }

    private Window getParentWindow() {
        return SwingUtilities.getWindowAncestor(this);
    }

    /**
     * Reusable cell renderer for {@link PollerConfig} entries.
     * A single label instance is reconfigured per call rather than allocated fresh,
     * avoiding per-paint object allocation that degrades rendering performance.
     */
    private static class PollerConfigRenderer extends ThemedLabel implements ListCellRenderer<PollerConfig> {

        private PollerConfigRenderer() {
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends PollerConfig> list,
                PollerConfig config,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            setText("<html><b>" + config.repositoryName() + "</b>&nbsp;&nbsp;"
                + "<span style='color:gray'>" + config.repositoryUrl() + "</span>&nbsp;"
                + "<i>(" + (config.pollIntervalMs() / 1000) + "s)</i></html>");
            if (isSelected) {
                setBackground(list.getSelectionBackground());
                setForeground(list.getSelectionForeground());
            } else {
                setBackground(list.getBackground());
                setForeground(list.getForeground());
            }
            return this;
        }
    }
}
