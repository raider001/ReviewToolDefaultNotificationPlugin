package com.kalynx.serverlessreviewtool.defaulttoolnotificationplugin.sse;

/**
 * Common object produced by {@link IndexerSseListener} for every complete SSE frame.
 * Carries only raw field values — no routing or payload construction.
 *
 * @param eventType     raw event type string from the SSE frame (e.g. {@code "review.created"})
 * @param reviewId      value of {@code review_id} in the JSON payload; {@code null} if absent
 * @param repository    value of {@code repository}; present on review and branch events
 * @param repositoryUrl value of {@code repository_url}; present on branch and comment events
 * @param branchName    value of {@code branch_name}; present on branch events
 * @param commentId     value of {@code comment_id}; present on comment events
 */
public record SseEvent(
        String eventType,
        String reviewId,
        String repository,
        String repositoryUrl,
        String branchName,
        String commentId) {
}
