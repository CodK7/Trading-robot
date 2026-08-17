# Security review

## Scope

This app performs only explicit XAUUSD market-order button requests in MetaTrader 5 at a fixed 0.01 lot. It contains no market analysis, signal generation, prediction, or automatic trigger.

## Verified design constraints

- Accessibility XML restricts observed windows to `net.metaquotes.metatrader5`; the service also checks the package before traversing nodes.
- The service requires MT5 to be the visible app, a visible Demo/Trial account label, exactly one XAUUSD-family symbol, confirmed market-execution text, one lot field, and one matching Buy or Sell button. Any ambiguity is refused.
- Text is supplied through `ACTION_SET_TEXT`; no clipboard access is used. The fixed 0.01 value is read back before a button click.
- A single in-flight gate and cancellation generation prevent duplicate clicks and invalidate pending work immediately on Stop.
- The local operation log is encrypted with an Android Keystore AES-GCM key. Backups and device-transfer extraction are disabled.
- The manifest requests only foreground-service permissions required for the visible automation notification. It has no INTERNET, overlay, clipboard, storage, location, account, or notification runtime permission.
- Release builds enable R8 code shrinking and resource shrinking. Signing values are read only from process environment variables; keys and passwords are excluded from source control.

## Operational note

Android Accessibility is a sensitive capability. Users must deliberately enable the service and manually focus the intended lot field before each request.
