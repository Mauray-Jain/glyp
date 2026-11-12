# Architecture decisions

| # | Decision |
|---|---|
| [0001](0001-light-document.md) | One light document owns the light track |
| [0002](0002-take-and-clock.md) | Takes run on one clock seam and one render tick |
| [0003](0003-session-state-machine.md) | A session state machine decides what the Studio may do |
| [0004](0004-ringtone-export.md) | Ringtone export is a pure module plus one install adapter |
| [0005](0005-song.md) | A song module decodes once and owns the time base |
| [0006](0006-light-shapes.md) | One trail and one level curve for every light shape |
| [0007](0007-glyph-connection.md) | One Glyph binding per process; only the session follows visibility |

These were written during an unattended refactor; each records the recommended option and the
alternatives so they can be revisited.
