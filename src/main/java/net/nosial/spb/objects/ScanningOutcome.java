package net.nosial.spb.objects;

import net.nosial.jfederation.enums.SuggestedAction;
import net.nosial.spb.enums.ModerationAction;

import java.util.List;

/**
 * Immutable snapshot of the outcome of one automated content scan.
 *
 * <p>Carries every value describing how a scan resolved: the content and entity suggestions
 * reported by the Federation server, what was deleted, restricted, or applied, and whether
 * automated moderation was skipped entirely. Instances are produced by {@link #builder()}.
 *
 * @param contentSuggestion the suggested action for the scanned content, or {@code null} when the content was not scanned
 * @param entitySuggestion the suggested action for the message author entity, or {@code null} when the entity was not queried
 * @param deletedMessageIds the ids of every message that was actually deleted (empty when none)
 * @param contentRemovalRequested whether content removal was requested regardless of outcome
 * @param mediaRestrictionRequested whether a text restriction (media-only posting) was requested
 * @param mediaRestrictionApplied whether the text restriction was actually applied
 * @param entityAction the resolved entity moderation action (never {@code null})
 * @param entityActionApplied whether the entity action was actually applied
 * @param textRestrictionApplied whether the strict text restriction was actually applied
 * @param automatedModerationSkipped whether automated moderation was skipped entirely
 */
public record ScanningOutcome(
        SuggestedAction contentSuggestion,
        SuggestedAction entitySuggestion,
        List<Integer> deletedMessageIds,
        boolean contentRemovalRequested,
        boolean mediaRestrictionRequested,
        boolean mediaRestrictionApplied,
        ModerationAction entityAction,
        boolean entityActionApplied,
        boolean textRestrictionApplied,
        boolean automatedModerationSkipped)
{
    /**
     * Records a scan that acted on the content and possibly on its author.
     *
     * <p>Album restrictions are decided per album rather than per message, so they are not part of
     * this; a scan of one part of an album reports what happened to that part.
     *
     * @param contentSuggestion what Federation suggested about the content, or {@code null}
     * @param entitySuggestion what Federation suggested about the author, or {@code null}
     * @param deletedMessageIds the messages that were actually deleted
     * @param contentRemovalRequested whether removal was asked for, whatever came of it
     * @param entityAction the action resolved for the author
     * @param entityActionApplied whether that action was actually applied
     * @param textRestrictionApplied whether the author was restricted to text
     */
    public ScanningOutcome(SuggestedAction contentSuggestion, SuggestedAction entitySuggestion,
                           List<Integer> deletedMessageIds, boolean contentRemovalRequested,
                           ModerationAction entityAction, boolean entityActionApplied,
                           boolean textRestrictionApplied)
    {
        this(contentSuggestion, entitySuggestion, deletedMessageIds, contentRemovalRequested, false, false,
                entityAction, entityActionApplied, textRestrictionApplied, false);
    }

    /**
     * Normalises the message id list so callers never have to pass an empty one.
     */
    public ScanningOutcome
    {
        deletedMessageIds = deletedMessageIds == null ? List.of() : List.copyOf(deletedMessageIds);
    }

}
