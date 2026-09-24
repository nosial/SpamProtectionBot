package net.nosial.spb.enums;

/**
 * The resolved moderation action to take (or not take) on a user.
 */
public enum ModerationAction
{
    NONE,
    TEMPORARY_BAN,
    PERMANENT_BAN,
    TEMPORARY_RESTRICT,
    PERMANENT_RESTRICT
}