# MessageHub V4

Android local notification organizer for WeChat, QQ, WhatsApp.

## Improvements
- Strip QQ notification count suffixes, e.g. `(3条新消息)` and `（10条新消息）`.
- On database upgrade, merge folders with the same normalized name, platform, and kind without deleting message rows.
- New incoming messages reuse matching normalized folders.
- Keep group and personal chats separate; cross-platform contacts require manual linking.
- Retain per-message deletion, TXT import, and manual outgoing message entry.

## Build from GitHub on a phone
Upload this project's contents to a GitHub repository, open Actions > Build MessageHub V4 APK > Run workflow, then download `MessageHub-V4-debug-APK` from the completed run's Artifacts.

## Limitations
Notification access must be enabled explicitly. Notifications do not guarantee complete chat history, group identity, @mentions, or outgoing messages. Name-only matching can combine distinct same-name people; review folders before relying on them. No cloud AI calls are made. APK is debug-signed. Install as an update only if the signing certificate matches your existing installed APK; otherwise back up records before uninstalling the older app. A successful build has not been verified locally.
