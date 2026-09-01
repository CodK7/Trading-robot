# Security review

## Scope

This app performs only explicit XAUUSD market-order button requests in MetaTrader 5 at a fixed 0.01 lot. Its yellow offset is adjusted manually by +/− and is not market data. It contains no market analysis, signal generation, prediction, or live-price trigger.

## Verified design constraints

- Accessibility XML restricts observed windows to `net.metaquotes.metatrader5`; the service also checks the package before traversing nodes.
- The service requires MT5 to be the visible app, exactly one XAUUSD-family symbol, market-order controls, one lot field, and one matching Buy or Sell button. Any ambiguity is refused. Demo and real accounts are supported; a visible Exness label is recorded when available.
- Text is supplied through `ACTION_SET_TEXT`; no clipboard access is used. The fixed 0.01 value is read back before a button click.
- A single in-flight gate prevents duplicate clicks. The final validity check and MT5 click share the same lock, so no click can occur after Stop returns.
- The local operation log is encrypted with an Android Keystore AES-GCM key. Backups and device-transfer extraction are disabled.
- Command delivery is in-process. Notification actions target a non-exported receiver with immutable PendingIntents, preventing the pre-Android-13 broadcast injection found in the earlier design.
- The manifest requests only foreground-service and notification permissions. It has no INTERNET, overlay, clipboard, storage, location, or account permission.
- Release builds enable R8 code shrinking and resource shrinking. Signing values are read only from process environment variables; keys and passwords are excluded from source control.

## Operational note

Android Accessibility is a sensitive capability. Users must deliberately enable the service and prepare the intended XAUUSD market-order screen before each request.
