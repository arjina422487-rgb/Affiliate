# Affiliate 2.8.0 UI refresh

- Violet/teal light and dark palettes with system fonts and existing RTL support.
- Shared gradient backdrop and translucent glass-style surfaces, with layered edge highlights; no expensive full-screen blur or continuous background animation.
- Floating navigation with animated selected icons and screen crossfades.
- Redesigned welcome screen, login/registration glass panels, keyboard-aware scrolling and minimum-height buttons.
- Updated hero cards, task cards, activity/wallet/settings surfaces and gradient profile-photo frame.
- Task footer wraps on narrow layouts. Main/auth content is centered with a 720dp maximum width, navigation capped at 560dp; dp/sp retain display-density and font-scale behavior.
- No changes to permissions, pairing, server endpoint, task submission or reward processing.

Validation: debug Kotlin compilation passed. Release build and signature verification recorded when completed. No physical-device visual QA or Android 10–17 device matrix has been completed. This is a glass-inspired rendering treatment, not a system backdrop-blur implementation. App strings remain primarily English; worldwide localization has not been added.

Release assembleRelease and lintVitalRelease completed successfully. APK signature verified with apksigner (v2), version 2.8.0/code 15, minSdk 29. Delivered to Downloads/Affiliate-2.8.0.apk.
