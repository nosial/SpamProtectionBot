# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.10] - 2026-10-01

This is a regular update, following version `v1.0-R3` of the OFD Specification.

### Changed
 - Updated all required dependencies to the latest compatible version
 - The report dialog no longer offers the Illegal Content incident type when the Federation server does not accept
   illegal content, and `/report illegal` (or a forwarded report of that type) replies that the server does not
   accept it instead of failing with a generic error



## [1.0.9] - 2026-09-29

This is an ongoing update

### Added
 - Scanning, Join Protection and Secretary Mode notifications now carry a button opening the member's or contact's
   entity in the Federation Web Application, and `/link` replies with buttons for both the entity and its target
 - Reporting a false positive now adds a button opening the submitted report to the notification

### Changed
 - The `#REPORT_ASSIGNED` buttons are now labelled Just Close, Normal, Suspicious and Malicious
 - Pressing a `#REPORT_ASSIGNED` button now checks the report first; a report that was already closed elsewhere, or no
   longer exists, has its buttons removed with an alert saying so instead of sending a close request the server would
   reject


## [1.0.8] - 2026-09-28

This update introduces integration with [FederationWeb](https://github.com/nosial/FederationWeb) and improvments
to the bot interface.

### Added
 - Added the optional `federation.web_application_endpoint` setting; when set, every message that displays a Federation
   report, evidence, entity or blacklist record carries a button opening it in the Federation Web Application, and the
   button is kept when a notification's action buttons are removed

### Changed
 - Passive chats are no longer notified when Federation only suggests treating a message or its author with caution;
   Moderate and Strict delete a cautioned message that contains media or links and send a notification, and leave a
   cautioned plain-text message alone
 - A scanning notification is now also sent when the action it reports failed, saying what could not be done, instead
   of being dropped

### Fixed
 - Fixed scanning notifications re-sending a deleted message's text and attachments as the bot's own messages; the
   message (or every part of an album) is now forwarded to each moderator and linked chat before it is deleted or its
   author is acted on, and the notification replies to the forwarded copy. When a message cannot be forwarded, the
   notification is sent on its own and the message is never reproduced



## [1.0.7] - 2026-09-27

This update introduces critical bug fixes for notifications

### Changed
 - Pressing Report False Positive no longer sends new messages to every moderator; only the notification it was
   pressed on is updated with the report ID, and moderators pressing it on their own copies afterwards are told the
   report was already submitted
 - `#REPORT_ASSIGNED` notifications are now followed by each of the report's evidence records as a reply, with the
   evidence's file attachments replying to it, so operators can review the report and its evidence together

### Fixed
 - Fixed scanning notifications (`#SCAN_MATCH`, `#CONTENT_DELETED`, `#MEMBER_RESTRICTED`, `#MEMBER_BANNED`) not
   showing the message they were about; the message is now forwarded with the notification replying to it, or its
   captured text and attachments are re-sent when it was deleted


## [1.0.6] - 2026-09-27

This update introduces a bug fix for operators

### Fixed
 - Fixed the Close and classification buttons on operator report notifications doing nothing when pressed; the
   check that the button was pressed in the operator's own private chat compared boxed Telegram IDs by reference,
   so it rejected every real operator



## [1.0.5] - 2026-09-26

This update introduces bug fixes and code maintainability changes.

### Changed
 - Operator report notification buttons no longer expire after 8 hours or on a bot restart; they carry the report UUID
   themselves and stay usable until the report is closed, acting with the operator's current credential
   ([#12](https://github.com/nosial/SpamProtectionBot/issues/12))
 - Moderator Delete / Mute / Ban buttons on report notifications no longer expire after 10 minutes or on a bot
   restart; they carry the reported message themselves, and the moderator's administrator rights are checked again
   when a button is pressed
 - Closing a report from an operator notification now removes the buttons and replies with the result instead of
   replacing the notification, and a failed close leaves the notification and its buttons in place for another attempt
 - Only moderators of the protected chat can use the Report False Positive button, which could previously be pressed
   by any member of a linked chat that received the notification
 - Callback alerts and toasts now convert HTML to plain text, so markup can no longer appear as raw tags

### Fixed
 - Fixed the "Report Action Expired" alert showing raw `<b>` tags ([#13](https://github.com/nosial/SpamProtectionBot/issues/13))
 - Fixed expired operator report actions replacing the whole notification, which hid the report UUID; buttons sent
   before this update now act on the report UUID shown in the notification
   ([#11](https://github.com/nosial/SpamProtectionBot/issues/11))
 - Fixed expired moderator report buttons claiming the report "has already been processed"
 - Fixed an expired Report False Positive button staying on the notification
 - Fixed another member pressing a group settings menu replacing it with an "expired" message for its owner
 - Fixed expired settings menus always being shown in the default language, and Secretary settings pointing to
   `/start` in a group instead of `/settings`


## [1.0.4] - 2026-09-25

This update introduces bug fixes

### Changed
 - `/report` and `/blacklist` now explain, in regular groups, that Telegram does not show the bot replies to messages
   sent before it joined, instead of only repeating their usage ([#8](https://github.com/nosial/SpamProtectionBot/issues/8))

### Fixed
 - Fixed never-updated evidence, reports and entities showing `1970-01-01 00:00:00 UTC` as their last update; they now
   show `Never` ([#9](https://github.com/nosial/SpamProtectionBot/issues/9))
 - Fixed `/help` not responding in group chats; the menu is now sent as an ephemeral message visible only to the caller
   ([#10](https://github.com/nosial/SpamProtectionBot/issues/10))


## [1.0.3] - 2026-09-25

This update introduces interface improvements and important bug fixes.

### Added
 - Added `BotMembershipHandler` so the bot explains the next steps when added to a group: it lists the permissions it 
   needs, or opens the settings menu once it has them ([#6](https://github.com/nosial/SpamProtectionBot/issues/6))
 - Introduced `EntityPublisher` to publish Telegram entities to Federation before a report or blacklist needs them,
   respecting the chat's privacy mode

### Changed
 - Renamed the `Operator` and `Management` permissions to `Operator Administration` and `Server Management` in `/authinfo` and `/ping` ([#5](https://github.com/nosial/SpamProtectionBot/issues/5))
 - Renamed the `/start` server section to `Federation Information` and removed its redundant footer
 - Merged the `/ping` Federation status and statistics into a single `Federation Server` section

### Fixed
 - Fixed reports of forwarded messages failing with `Reporting entity not found`: private chats with the bot now always
   push entities, and forwarded authors are read from `forward_origin` ([#4](https://github.com/nosial/SpamProtectionBot/issues/4))
 - Fixed `/blacklist` failing for entities unknown to Federation, and the explicit
   `/blacklist <identifier> <report_uuid> <type> <expires>` form ignoring its report UUID
 - Fixed reply commands such as `/info` being ignored in a forum's General topic, by answering in a topic only when
   the message belongs to one (`MessageHelper.topicId`) ([#7](https://github.com/nosial/SpamProtectionBot/issues/7))



## [1.0.2] - 2026-09-24

This update introduces some minor bug fixes.

### Fixed 
 - Refactored admin permission checks, improved fallback logic for cache reads, and added chat migration methods
 - Replaced `sendText` with `sendHtml` in `ReportHandler` and updated `MessageHelper` logic for topic message validation. Removed unused `sendText` method from `Handler`.
 - Handled null language resolution for outdated menu buttons and missing translations in `ConfigurationHandler`, `RegistrationHandler`, and `LanguageHandler`.
 - Refactored `ScanningHandler` to prevent redundant scans for membership service messages and improved Passive mode notification logic with `passiveObservationDue`.


## [1.0.1] - 2026-09-24

Set transaction mode to IMMEDIATE to prevent SQLITE_BUSY errors and added test to validate transaction behavior


## [1.0.0] - 2026-09-24

Initial release of SpamProtectionBot
