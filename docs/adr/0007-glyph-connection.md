# 7. One Glyph binding per process; only the session follows visibility

Status: accepted

## Context

`GlyphController` used to bind to Nothing's Glyph service when the app came to the foreground and
unbind when it left. A bind that was lost, or one that never connected, left the app stuck on
`CONNECTING` with the lights dark: the SDK never re-binds on its own. Separately, Nothing OS can
keep the Glyphs dark for its own reasons (the Glyph Interface switched off by its tile or bedtime
schedule, or the phone lying screen-up), and the app couldn't tell the user why.

## Decision

- Bind once per process, on the first `enterForeground`, and keep the binding.
  `MainActivity.onStart/onStop` only open and close a **session**.
- Going to the background sends an all-zero frame, then `turnOff()` and `closeSession()`;
  unbinding used to clear the lights, and staying bound they must be blanked explicitly.
- While visible and not registered, a watchdog binds again after 2 s, 4 s, 8 s, then every 15 s.
  Coming back to the foreground with a lost bind retries at once.
- While visible, watch two `Settings.Global` keys: `led_effect_enable` (0 = Glyph Interface off)
  and `glyph_screen_upward_state` (1 = lying screen-up). They map to the `GLYPHS OFF` and
  `SCREEN UP` states. Frames keep flowing in both, so the lights come back the moment Nothing OS
  allows them.
- Android and SDK calls sit behind `GlyphPlatform`, so this state machine is unit-tested
  (`GlyphControllerTest`).

## Alternatives considered

- **Bind and unbind with visibility** (the original design). Every trip to the background was
  another chance for a lost bind to leave the app stuck.
- **Keep a session open in the background.** Nothing's SDK only serves foreground apps.
