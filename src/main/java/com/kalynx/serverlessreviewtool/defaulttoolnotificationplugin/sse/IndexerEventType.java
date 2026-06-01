package com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.sse;

import java.util.HashMap;
import java.util.Map;

/**
 * State machine that maps raw Central Indexer SSE event type strings to canonical types.
 *
 * <p>Each constant is a state. {@link #from(String)} is the transition function — it maps
 * any accepted alias (including legacy uppercase variants) to its canonical state in O(1).
 * The alias map is built once at class-load time.
 */
public enum IndexerEventType {

    REVIEW_CREATED ("review.created",  "review_created"),
    REVIEW_UPDATED ("review.updated",  "review_updated",  "review.closed",  "review_closed"),
    BRANCH_UPDATED ("branch.updated",  "branch_updated"),
    BRANCH_DELETED ("branch.deleted",  "branch_deleted"),
    COMMENT_ADDED  ("comment.added",   "review_comment_added"),
    COMMENT_UPDATED("comment.updated", "review_comment_updated"),
    UNKNOWN;

    private static final Map<String, IndexerEventType> ALIAS_MAP = new HashMap<>();

    static {
        for (IndexerEventType type : values()) {
            for (String alias : type.aliases) {
                ALIAS_MAP.put(alias, type);
            }
        }
    }

    private final String[] aliases;

    IndexerEventType(String... aliases) {
        this.aliases = aliases;
    }

    IndexerEventType() {
        this.aliases = new String[0];
    }

    public static IndexerEventType from(String raw) {
        if (raw == null) return UNKNOWN;
        return ALIAS_MAP.getOrDefault(raw.toLowerCase(), UNKNOWN);
    }
}
