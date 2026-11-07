# hradar macOS application icon

`hradar-source.png` is the single approved master (1254×1254, 8-bit RGBA).
It was copied from the owner's Desktop file `Неоновый радар с буквой «h».png`;
the Desktop original was neither moved nor modified.

Only detached outer color artifacts were cleaned: alpha was set to zero outside
a radius of 485 pixels about (621, 614). This leaves a margin beyond the radar's
approximately 467-pixel outer ring and retains its adjacent glow. The decoded RGBA
pixels inside that boundary and RGB channels everywhere remain exactly original;
20,126 outer alpha values changed. No generated/redrawn artwork is used.

Generate with standard macOS tools, on Intel or ARM64:

```sh
bash scripts/generate-macos-icon.sh
```

Output: `.tdlib-build/branding/hradar.icns` (ignored build artifact). The script
resizes directly from the master with `sips`, emits the ten standard iconset
representations (16, 32, 128, 256, 512 points at 1×/2×), then runs `iconutil`.
An optional first argument selects an output path. No graphics dependency is added.

`package-macos.sh` generates this resource and passes it to `jpackage --icon`
before final ad-hoc or optional Developer ID signing. The bundle verifier requires
`CFBundleIconFile=hradar.icns` and exact resource bytes, rejecting the default Java
icon or later replacement. The bundle remains `hradar.app` / `dev.hradar.desktop`;
Menu Bar's template glyph and React/browser assets are separate and unchanged.

Regression: `python3 -B -m unittest discover -s tests -p test_macos_icon.py -v`.
Native generation checks run on macOS; other platforms skip this native-only test.
