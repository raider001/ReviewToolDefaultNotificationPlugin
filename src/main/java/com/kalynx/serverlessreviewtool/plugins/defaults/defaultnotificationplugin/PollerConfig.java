package com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin;

/**
 * Configuration for polling a single repository.
 *
 * @param repositoryName display name of the repository
 * @param repositoryUrl remote URL of the repository
 * @param pollIntervalMs polling interval in milliseconds
 */
public record PollerConfig(String repositoryName, String repositoryUrl, long pollIntervalMs) {
}

