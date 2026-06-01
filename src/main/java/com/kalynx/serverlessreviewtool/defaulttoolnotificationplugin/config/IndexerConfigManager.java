package com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Single source of truth for the current {@link IndexerConfig}.
 *
 * <p>Owns the loader and saver. All other classes that need config hold a reference to
 * this manager and call {@link #get()} rather than storing a config snapshot themselves.
 * This ensures config changes propagate automatically without requiring restarts or
 * explicit re-injection.
 */
public class IndexerConfigManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(IndexerConfigManager.class);

    private final IndexerConfigLoader loader;
    private final IndexerConfigSaver saver;
    private volatile IndexerConfig current;

    public IndexerConfigManager() {
        this(new IndexerConfigLoader(), new IndexerConfigSaver());
    }

    IndexerConfigManager(IndexerConfigLoader loader, IndexerConfigSaver saver) {
        this.loader = loader;
        this.saver = saver;
        this.current = loader.load();
    }

    /** Creates a manager pre-loaded with the given config, no disk access. */
    public IndexerConfigManager(IndexerConfig initial) {
        this.loader = new IndexerConfigLoader();
        this.saver = new IndexerConfigSaver();
        this.current = initial;
    }

    /**
     * Returns the current configuration. Always reflects the most recently saved or loaded state.
     */
    public IndexerConfig get() {
        return current;
    }

    /**
     * Persists {@code config} to disk and updates the held reference.
     *
     * @param config the new configuration to persist
     * @throws IOException if the file cannot be written
     */
    public void save(IndexerConfig config) throws IOException {
        saver.save(config);
        current = config;
        LOGGER.info("Configuration updated");
    }

    /**
     * Re-reads the configuration file from disk and updates the held reference.
     * Use this when an external process may have changed the file.
     */
    public void reload() {
        current = loader.load();
        LOGGER.info("Configuration reloaded from disk");
    }
}
