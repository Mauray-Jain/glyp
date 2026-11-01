# 6. One trail and one level curve for every light shape

Status: accepted

## Context

Three places drew a "bright head with a fading tail": the Sweep edit tool, the Sweep pad and the
Flow style's comets, each with its own head/tail arithmetic. Three places turned a 0..1 level
into a stored value: the composer with a 1.6 gamma, the edit tools and the pads linearly. So 50 %
brightness meant different things depending on which tool wrote it.

## Decision

`studio/LightShapes` holds:

- `trail(zones, head, tail, strength, out)`, the one trail shape, max-blended into a level
  buffer; callers decide only where the head is.
- `stored(level)`, the one level curve (gamma 1.6 into 0..4095), used by the composer, the edit
  tools and pad recording/jamming.

Every writer now builds levels in 0..1 and stores them through the same curve.

## Consequences

Edit tools and pads now use the perceptual curve: a 50 % fade or pad brightness is visibly half as
bright on the Glyphs instead of looking almost full. Existing projects are unaffected (stored
values are not rewritten). The pad and sweep timings were kept.

## Alternatives considered

- **Linear everywhere.** Matches the old tools but makes composed tracks look flat; the gamma was
  added to the composer for exactly that reason.
- **Per-tool curves as parameters.** Keeps the inconsistency the step was meant to remove.
