package net.nosial.spb.objects.database;

import net.nosial.spb.classes.managers.OperatorManager;

/**
 * Immutable snapshot of the operator identity a Telegram user has authenticated with.
 *
 * <p>Instances are produced by {@link OperatorManager} and hold
 * the credentials accepted by the Federation server for the linked operator.
 *
 * @param operatorUuid the UUID of the operator on the Federation server
 * @param accessToken the access token the operator authenticated with
 */
public record OperatorIdentity(String operatorUuid, String accessToken)
{
}