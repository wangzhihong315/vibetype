[中文](../motion.md) · English

# Motion specification

Goal: all feedback completes within 300ms, so the user always knows whether "that tap was caught". Follow the rhythm of native iPhone apps: short, restrained, interruptible; no decorative animation.

## Duration

| Scenario | Duration | Curve |
|---|---|---|
| Key press | 110ms | ease-out |
| Key release | 140ms | spring (damping 0.85) |
| Recording overlay in/out | 220ms | ease-in-out |
| Result card in/out | 240ms | spring |
| Bottom sheet | 280ms | system default (M3) |
| Capsule save fly-in | 680ms | FastOutSlowIn |
| Page transition | 300ms | ease-in-out |

## Haptics

- Voice key / capsule key press: one `LongPress`
- Capsule saved successfully: one `LongPress`
- Failure: no vibration; explain with color and text

## Components

### Keys

- Press scales down to **0.97**, springs back to 1.0; nothing but color changes, no layout shift
- The capsule key opens the mic only after being held 240ms: a 240ms coral progress bar is drawn along the bottom
- The top-right of the capsule key shows the unsynced count (capped at `9+`), which disappears after sync

### Recording overlay

- Floats over the trackpad; no page change
- The glow follows the volume; the timer ticks once per second
- For long sentences only the latest 96 characters are shown (preceded by `…`), so the user sees "recognizing"

### Result card

- Success: collapses automatically after 5 seconds; over 40 characters it shows "N characters total · tap to read all"
- Failure: stays, can be tapped to resend
- No automatic retry: avoids typing twice

### Bottom sheet

- Always fully expanded (`skipPartiallyExpanded`), scrollable inside
- Swipe down to close; closing does not lose the draft (except when editing a capsule)

### Capsule fly-in

- The card shrinks from the center and flies to the capsule key in the bottom-left corner, 680ms
- Visual confirmation only; does not block the next recording

## Interruptibility

- Releasing during recording ends it immediately without waiting for animation
- After the result card appears the next recording can start directly; the old card yields immediately
- All animations affect only opacity / scale / translation, never layout size, to avoid touch-target drift
