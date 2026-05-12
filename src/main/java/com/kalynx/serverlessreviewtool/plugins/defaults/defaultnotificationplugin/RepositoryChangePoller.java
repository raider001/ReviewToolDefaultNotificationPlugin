package com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
/**
 * Polls a repository for changes using git ls-remote.
 * Detects when repository refs have changed without cloning.
 * Designed to be scheduled by an executor service.
 *
 * <p>Initial ref state is seeded on the first scheduled execution, not in the constructor,
 * so that constructing many pollers does not block the calling thread.
 */
public class RepositoryChangePoller implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryChangePoller.class);
    private final PollerConfig config;
    private final Consumer<PollerConfig> onChangeListener;
    private final Map<String, String> knownRefs = new HashMap<>();
    private boolean initialized = false;

    public RepositoryChangePoller(PollerConfig config, Consumer<PollerConfig> onChangeListener) {
        this.config = config;
        this.onChangeListener = onChangeListener;
    }

    @Override
    public void run() {
        if (!initialized) {
            queryRepositoryState();
            initialized = true;
            return;
        }
        checkForChanges();
    }
    public String getRepositoryName() {
        return config.repositoryName();
    }
    private void checkForChanges() {
        try {
            Map<String, String> currentRefs = queryRefs();
            if (hasChanged(currentRefs)) {
                knownRefs.clear();
                knownRefs.putAll(currentRefs);
                LOGGER.debug("Repository changed: {}", config.repositoryName());
                onChangeListener.accept(config);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to query repository '{}' during polling: {}", config.repositoryName(), e.getMessage());
        }
    }
    private void queryRepositoryState() {
        try {
            Map<String, String> refs = queryRefs();
            knownRefs.putAll(refs);
            LOGGER.debug("Initial state for {}: {} refs", config.repositoryName(), refs.size());
        } catch (Exception e) {
            LOGGER.warn("Failed to query initial state for '{}': {}", config.repositoryName(), e.getMessage());
        }
    }
    private Map<String, String> queryRefs() throws Exception {
        Map<String, String> refs = new HashMap<>();
        StringBuilder commandOutput = new StringBuilder();
        ProcessBuilder pb = new ProcessBuilder("git", "ls-remote", config.repositoryUrl());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                commandOutput.append(line).append(System.lineSeparator());
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    String hash = parts[0];
                    String ref = parts[1];
                    refs.put(ref, hash);
                }
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String output = commandOutput.toString().trim();
            throw new Exception("git ls-remote failed with exit code " + exitCode + " for '" +
                config.repositoryUrl() + "'" + (output.isEmpty() ? "" : ": " + output));
        }
        return refs;
    }
    private boolean hasChanged(Map<String, String> currentRefs) {
        if (currentRefs.size() != knownRefs.size()) {
            return true;
        }
        for (Map.Entry<String, String> entry : currentRefs.entrySet()) {
            if (!entry.getValue().equals(knownRefs.get(entry.getKey()))) {
                return true;
            }
        }
        return false;
    }
}
