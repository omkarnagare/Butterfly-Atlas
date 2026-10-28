App icons, generated ONCE from the Centaur Oakblue "at rest" logo on its meadow tile (the logo design is kept with the
project's private documents), and committed so nobody building the packages needs image tools; these files are the source:

  butterfly-atlas-1024.png   the master (1024x1024, transparent corners)
  butterfly-atlas.png        512x512, used for Linux (.deb and .rpm) and the browser-tab fallback
  butterfly-atlas.ico        Windows: 16, 24, 32, 48, 64, 128 and 256 px
  butterfly-atlas.icns       macOS: made with iconutil from a 16..512 px (and @2x) iconset

To regenerate the smaller sizes: start from butterfly-atlas-1024.png, write the .png/.ico with Pillow and the .icns with
`iconutil -c icns`.
