# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.4]

This is an ongoing update


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
