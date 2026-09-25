# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.3] - Ongoing

This is an ongoing update


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
