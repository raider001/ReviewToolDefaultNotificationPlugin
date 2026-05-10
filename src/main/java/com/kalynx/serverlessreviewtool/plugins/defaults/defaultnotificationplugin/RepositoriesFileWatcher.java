package com.kalynx.serverlessreviewtool.plugins.defaults.defaultnotificationplugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.function.Consumer;

/**
 * Watches repositories.json for changes and validates the configuration.
 *
 * <p>On file change:
 * 1. Reads the new content
 * 2. Validates it using RepositoriesValidator
 * 3. If valid, invokes the onConfigChanged callback
 * 4. If invalid, reverts the file to its previous known-good state
 */
public class RepositoriesFileWatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoriesFileWatcher.class);

    private final Path repositoriesFilePath;
    private final Consumer<String> onConfigChanged;
    private final RepositoriesValidator validator;

    private String lastValidContent;
    private WatchService watchService;

    /**
     * Creates a file watcher for repositories.json.
     *
     * @param repositoriesFilePath path to the repositories.json file
     * @param onConfigChanged      callback invoked with new JSON content when valid changes are detected
     */
    public RepositoriesFileWatcher(Path repositoriesFilePath, Consumer<String> onConfigChanged) {
        this.repositoriesFilePath = repositoriesFilePath;
        this.onConfigChanged = onConfigChanged;
        this.validator = new RepositoriesValidator();
    }

    /**
     * Starts watching the repositories file for modifications.
     */
    public void start() {
        ensureRepositoriesFileExists(repositoriesFilePath);
        String initialContent = readFile(repositoriesFilePath);
        if (validator.validate(initialContent)) {
            lastValidContent = initialContent;
        } else {
            LOGGER.warn("Initial repositories.json failed validation");
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
            Path parent = repositoriesFilePath.toAbsolutePath().getParent();
            if (parent == null) {
                LOGGER.warn("Cannot watch repositories file without parent directory: {}", repositoriesFilePath);
                return;
            }

            parent.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE
            );

            Thread watcherThread = new Thread(this::watchLoop, "repositories-config-watcher");
            watcherThread.setDaemon(true);
            watcherThread.start();
            LOGGER.info("Repositories file watcher started for: {}", repositoriesFilePath);
        } catch (IOException e) {
            LOGGER.error("Failed to start repositories file watcher for {}", repositoriesFilePath, e);
        }
    }

    private void watchLoop() {
        Path watchedFileName = repositoriesFilePath.getFileName();
        if (watchedFileName == null) {
            return;
        }

        while (!Thread.currentThread().isInterrupted()) {
            try {
                WatchKey key = watchService.take();
                boolean relevantChange = false;

                for (WatchEvent<?> event : key.pollEvents()) {
                    Object context = event.context();
                    if (!(context instanceof Path changed)) {
                        continue;
                    }
                    if (changed.equals(watchedFileName)) {
                        relevantChange = true;
                    }
                }

                key.reset();

                if (relevantChange) {
                    processRepositoriesFileChange();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOGGER.error("Error while processing repositories file watch event", e);
            }
        }
    }

    private synchronized void processRepositoriesFileChange() {
        String newContent = readFile(repositoriesFilePath);

        if (!validator.validate(newContent)) {
            LOGGER.warn("Repositories configuration validation failed. Reverting to last known-good state.");
            if (lastValidContent != null) {
                try {
                    Files.writeString(repositoriesFilePath, lastValidContent, StandardCharsets.UTF_8);
                    LOGGER.info("Reverted repositories.json to last valid state");
                } catch (IOException e) {
                    LOGGER.error("Failed to revert repositories.json", e);
                }
            }
            return;
        }

        lastValidContent = newContent;
        LOGGER.info("Repositories configuration is valid. Reloading...");
        onConfigChanged.accept(newContent);
    }

    private void ensureRepositoriesFileExists(Path filePath) {
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(filePath)) {
                String minimalConfig = "{\"repositories\": []}";
                Files.writeString(filePath, minimalConfig, StandardCharsets.UTF_8);
                LOGGER.info("Created initial repositories.json at {}", filePath);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize repositories file: " + filePath, e);
        }
    }

    private String readFile(Path filePath) {
        if (!Files.exists(filePath)) {
            return "";
        }
        try {
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failed reading repositories file: {}", filePath, e);
            return "";
        }
    }
}

