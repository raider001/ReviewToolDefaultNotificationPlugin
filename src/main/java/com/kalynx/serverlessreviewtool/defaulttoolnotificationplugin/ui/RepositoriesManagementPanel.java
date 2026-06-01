package com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.ui;

import com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.config.IndexerConfig;
import com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.config.IndexerConfigManager;
import com.kalynx.swingtheme.themedcomponents.ThemedButton;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedTextField;
import net.miginfocom.swing.MigLayout;

import java.util.function.Consumer;

/**
 * Settings panel for configuring the Central Indexer connection.
 * Displayed in the application's plugin settings area.
 */
public class RepositoriesManagementPanel extends ThemedPanel {

    private final ThemedTextField urlField;
    private final ThemedTextField tokenField;
    private final Consumer<IndexerConfig> onSave;

    public RepositoriesManagementPanel(IndexerConfigManager configManager, Consumer<IndexerConfig> onSave) {
        this.onSave = onSave;

        IndexerConfig config = configManager.get();
        urlField   = new ThemedTextField(config.indexerUrl());
        tokenField = new ThemedTextField(config.bearerToken());

        setLayout(new MigLayout("insets 12", "[right][grow, fill]", "[]8[]16[]"));

        add(new ThemedLabel("Indexer URL:"));
        add(urlField, "wrap");

        add(new ThemedLabel("Bearer Token:"));
        add(tokenField, "wrap");

        ThemedButton saveBtn = new ThemedButton("Save");
        saveBtn.addActionListener(e -> save());
        add(saveBtn, "skip 1, right");
    }

    private void save() {
        onSave.accept(new IndexerConfig(
                urlField.getText().trim(),
                tokenField.getText().trim(),
                java.util.List.of()));
    }
}
