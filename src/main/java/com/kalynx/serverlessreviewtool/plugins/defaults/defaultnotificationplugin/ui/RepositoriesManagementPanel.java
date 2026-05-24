package com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.ui;

import com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.IndexerConfig;
import com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.IndexerConfigLoader;
import com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin.IndexerConfigSaver;
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
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Panel for configuring the Central Indexer connection and the repositories to monitor.
 *
 * <p>The top section exposes the global indexer settings (URL, bearer token).
 * Changes to those fields are persisted when each field loses focus.
 *
 * <p>The bottom section lists monitored repositories and provides controls to add, edit,
 * and remove entries.  Changes to the list are persisted immediately.
 *
 * <p>An optional {@code onConfigChanged} callback supplied at construction time is invoked
 * after every successful save, allowing the owning plugin to restart its SSE listeners.
 */
public class RepositoriesManagementPanel extends ThemedPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoriesManagementPanel.class);

    private final IndexerConfigLoader loader;
    private final IndexerConfigSaver saver;
    private final Runnable onConfigChanged;

    private final ThemedTextField indexerUrlField  = new ThemedTextField(30);
    private final ThemedTextField bearerTokenField = new ThemedTextField(30);

    private final List<IndexerConfig.RepositoryEntry> allRepositories = new ArrayList<>();
    private final DefaultListModel<IndexerConfig.RepositoryEntry> listModel = new DefaultListModel<>();
    private final ThemedList<IndexerConfig.RepositoryEntry> repositoryList = new ThemedList<>(listModel);
    private final ThemedTextField searchField  = new ThemedTextField(20);
    private final ThemedButton    addButton    = new ThemedButton("Add");
    private final ThemedButton    editButton   = new ThemedButton("Edit");
    private final ThemedButton    removeButton = new ThemedButton("Remove");
    private final ThemedLabel     statusLabel  = new ThemedLabel(" ");

    /**
     * Creates a new {@code RepositoriesManagementPanel}.
     *
     * @param loader          reads the current configuration from disk
     * @param saver           persists configuration changes to disk
     * @param onConfigChanged called after every successful save so the owning plugin can
     *                        restart its SSE listeners; may be {@code null}
     */
    public RepositoriesManagementPanel(IndexerConfigLoader loader, IndexerConfigSaver saver, Runnable onConfigChanged) {
        this.loader = loader;
        this.saver = saver;
        this.onConfigChanged = onConfigChanged != null ? onConfigChanged : () -> {};
        setBorder(ThemedTitledBorder.create("Central Indexer"));
        configureLayout();
        setupListeners();
        loadSettings();
    }

    private void configureLayout() {
        setLayout(new MigLayout("fill, insets 10", "[grow]", "[][grow][]"));
        add(buildConnectionPanel(), "growx, wrap");
        add(buildRepositorySection(), "grow, wrap");
        add(statusLabel, "growx");
    }

    private ThemedPanel buildConnectionPanel() {
        ThemedPanel panel = new ThemedPanel();
        panel.setBorder(ThemedTitledBorder.create("Connection"));
        panel.setLayout(new MigLayout("", "[][grow]", "[]8[]"));

        panel.add(new ThemedLabel("Indexer URL:"),  "cell 0 0");
        panel.add(indexerUrlField,                  "cell 1 0, growx");
        panel.add(new ThemedLabel("Bearer token:"), "cell 0 1");
        panel.add(bearerTokenField,                 "cell 1 1, growx");
        return panel;
    }

    private ThemedPanel buildRepositorySection() {
        ThemedPanel panel = new ThemedPanel();
        panel.setBorder(ThemedTitledBorder.create("Repositories"));
        panel.setLayout(new MigLayout("fill, insets 5", "[grow][]", "[]8[grow]"));

        searchField.putClientProperty("JTextField.placeholderText", "Search repositories...");
        panel.add(searchField, "cell 0 0 2 1, growx");

        repositoryList.setCellRenderer(new RepositoryEntryRenderer());
        panel.add(new ThemedScrollPane(repositoryList), "cell 0 1, grow");
        panel.add(buildButtonPanel(), "cell 1 1, growy");
        return panel;
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
        addButton.addActionListener(e -> onAdd());
        editButton.addActionListener(e -> onEdit());
        removeButton.addActionListener(e -> onRemove());
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { applyFilter(); }
            public void removeUpdate(DocumentEvent e)  { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        addSaveFocusListener(indexerUrlField);
        addSaveFocusListener(bearerTokenField);
    }

    private void addSaveFocusListener(ThemedTextField field) {
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                saveAll(null);
            }
        });
    }

    private void loadSettings() {
        IndexerConfig config = loader.load();
        indexerUrlField.setText(config.indexerUrl());
        bearerTokenField.setText(config.bearerToken());
        allRepositories.clear();
        allRepositories.addAll(config.repositories());
        applyFilter();
    }

    private void applyFilter() {
        String query = searchField.getText().trim().toLowerCase();
        IndexerConfig.RepositoryEntry previousSelection = repositoryList.getSelectedValue();
        listModel.clear();
        allRepositories.stream()
                .filter(r -> matchesFilter(r, query))
                .forEach(listModel::addElement);
        if (previousSelection != null) {
            repositoryList.setSelectedValue(previousSelection, true);
        }
        updateButtonStates();
    }

    private boolean matchesFilter(IndexerConfig.RepositoryEntry entry, String query) {
        if (query.isEmpty()) {
            return true;
        }
        return entry.name().toLowerCase().contains(query) || entry.location().toLowerCase().contains(query);
    }

    private void onAdd() {
        RepositoryEntryDialog dialog = new RepositoryEntryDialog(getParentWindow());
        dialog.setVisible(true);
        if (dialog.isConfirmed()) {
            IndexerConfig.RepositoryEntry entry = dialog.buildEntry();
            allRepositories.add(entry);
            saveAll("Repository added.");
            applyFilter();
            repositoryList.setSelectedValue(entry, true);
        }
    }

    private void onEdit() {
        IndexerConfig.RepositoryEntry existing = repositoryList.getSelectedValue();
        if (existing == null) {
            return;
        }
        RepositoryEntryDialog dialog = new RepositoryEntryDialog(getParentWindow(), existing);
        dialog.setVisible(true);
        if (dialog.isConfirmed()) {
            IndexerConfig.RepositoryEntry updated = dialog.buildEntry();
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
        IndexerConfig.RepositoryEntry entry = repositoryList.getSelectedValue();
        if (entry == null) {
            return;
        }
        boolean confirmed = ThemedConfirmDialog.showConfirmation(
                getParentWindow(),
                "Remove Repository",
                "Remove '" + entry.name() + "'?");
        if (confirmed) {
            allRepositories.remove(entry);
            saveAll("Repository removed.");
            applyFilter();
        }
    }

    private void saveAll(String successMessage) {
        IndexerConfig config = buildCurrentConfig();
        try {
            saver.save(config);
            if (successMessage != null) {
                setStatus(successMessage);
            }
            onConfigChanged.run();
        } catch (IOException e) {
            LOGGER.error("Failed to save configuration", e);
            setStatus("Error saving configuration.");
        }
    }

    private IndexerConfig buildCurrentConfig() {
        return new IndexerConfig(
                indexerUrlField.getText().trim(),
                bearerTokenField.getText().trim(),
                List.copyOf(allRepositories));
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

    private static class RepositoryEntryRenderer extends ThemedLabel
            implements ListCellRenderer<IndexerConfig.RepositoryEntry> {

        private RepositoryEntryRenderer() {
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends IndexerConfig.RepositoryEntry> list,
                IndexerConfig.RepositoryEntry entry,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            setText("<html><b>" + entry.name() + "</b>&nbsp;&nbsp;"
                    + "<span style='color:gray'>" + entry.location() + "</span></html>");
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
