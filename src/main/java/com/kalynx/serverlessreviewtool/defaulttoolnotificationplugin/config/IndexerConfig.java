package com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.config;

import java.util.List;

/**
 * Immutable configuration for the Central Indexer notification plugin.
 *
 * @param indexerUrl   base URL of the Central Indexer (e.g. {@code http://localhost:8765})
 * @param bearerToken  Bearer token sent as {@code Authorization} on every request
 * @param repositories repositories to monitor
 */
public record IndexerConfig(
        String indexerUrl,
        String bearerToken,
        List<RepositoryEntry> repositories) {

    /**
     * A single repository tracked by the plugin.
     *
     * @param name     canonical repository ID used by the Central Indexer (e.g. {@code owner/repo})
     * @param location git clone URL or filesystem path used by the review tool application
     */
    public record RepositoryEntry(String name, String location) {}

    /**
     * Returns a default configuration pointing to a local indexer with no auth and no repositories.
     *
     * @return default config instance
     */
    public static IndexerConfig defaultConfig() {
        return new IndexerConfig("http://localhost:8765", "", List.of());
    }
}

