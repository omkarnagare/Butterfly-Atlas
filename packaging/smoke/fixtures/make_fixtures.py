"""Writes the four small photos the packaging smoke test adds (one per format, each with a GPS position), and raw.dng:
a DNG-shaped TIFF whose only image is a 1024 x 768 JPEG preview (Phase 7a: the built app must find and show it).

Run once by a developer (Pillow is needed here and nowhere else); the results are committed, so nobody building the
packages needs Python:  python3 packaging/smoke/fixtures/make_fixtures.py

heic.heic (Phase 7b: 1024 x 768, irot 1, GPS in Pune) is not made here: it comes from macOS's own HEIC encoder, through
the test-scope generator app/src/test/java/com/butterflyatlas/app/imaging/heif/HeicFixtures.java (its javadoc says how
to run it), which also makes the decoder check's two probe pictures in app/src/main/resources/heif/.
"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))

# (file, latitude, longitude): Bengaluru, Pune, Kolkata, Chennai (all inside India's bundled place list)
PHOTOS = [
    ("smoke_jpeg.jpg", 12.9716, 77.5946),
    ("smoke_png.png", 18.5204, 73.8567),
    ("smoke_tiff.tif", 22.5726, 88.3639),
    ("smoke_webp.webp", 13.0827, 80.2707),
]


def picture(seed):
    image = Image.new("RGB", (240, 160), (60 + seed * 30, 110, 150))
    draw = ImageDraw.Draw(image)
    for i in range(12):
        draw.ellipse((10 + i * 17, 20 + (i * seed * 7) % 90, 60 + i * 17, 70 + (i * seed * 7) % 90),
                     fill=(200 - i * 10, 90 + i * 8, 60 + seed * 20), outline=(20, 20, 20))
    return image


def rational(value):
    degrees = int(value)
    minutes = int((value - degrees) * 60)
    seconds = round(((value - degrees) * 60 - minutes) * 60, 2)
    return (float(degrees), float(minutes), float(seconds))


def tiff_with_gps(image, lat, lon):
    """A little-endian TIFF written by hand (Pillow's TIFF writer drops the GPS directory): RGB, uncompressed."""
    import struct
    image = image.resize((120, 80))
    pixels = image.tobytes()
    width, height = image.size

    def rationals(value):
        deg = int(value)
        minutes = int((value - deg) * 60)
        seconds = round(((value - deg) * 60 - minutes) * 60 * 100)
        return struct.pack("<6I", deg, 1, minutes, 1, seconds, 100)

    entries = 10
    ifd0 = 8
    ifd0_size = 2 + entries * 12 + 4
    bps = ifd0 + ifd0_size                  # 3 SHORTs
    gps = bps + 8                           # GPS IFD: 4 entries
    gps_size = 2 + 4 * 12 + 4
    lat_at = gps + gps_size
    lon_at = lat_at + 24
    strip = lon_at + 24

    def entry(tag, kind, count, value):
        return struct.pack("<HHII", tag, kind, count, value)

    ifd = struct.pack("<H", entries)
    ifd += entry(256, 4, 1, width) + entry(257, 4, 1, height) + entry(258, 3, 3, bps) + entry(259, 3, 1, 1)
    ifd += entry(262, 3, 1, 2) + entry(273, 4, 1, strip) + entry(277, 3, 1, 3) + entry(278, 4, 1, height)
    ifd += entry(279, 4, 1, len(pixels)) + entry(34853, 4, 1, gps) + struct.pack("<I", 0)
    gps_ifd = struct.pack("<H", 4)
    gps_ifd += entry(1, 2, 2, ord("N" if lat >= 0 else "S")) + entry(2, 5, 3, lat_at)
    gps_ifd += entry(3, 2, 2, ord("E" if lon >= 0 else "W")) + entry(4, 5, 3, lon_at) + struct.pack("<I", 0)
    return (b"II*\x00" + struct.pack("<I", 8) + ifd + struct.pack("<3H", 8, 8, 8).ljust(8, b"\x00") + gps_ifd
            + rationals(abs(lat)) + rationals(abs(lon)) + pixels)


def dng_with_preview(jpeg, width, height):
    """A little-endian DNG-shaped TIFF (DNGVersion 1.4) whose IFD0 is a reduced-resolution image: one strip, JPEG."""
    import struct
    make, model = b"Smoke\x00", b"Test camera\x00"
    entries = [  # (tag, type, count, value or offset placeholder)
        (254, 4, 1, 1), (256, 4, 1, width), (257, 4, 1, height), (258, 3, 3, None), (259, 3, 1, 7), (262, 3, 1, 6),
        (271, 2, len(make), None), (272, 2, len(model), None), (273, 4, 1, None), (274, 3, 1, 1), (277, 3, 1, 3),
        (278, 4, 1, height), (279, 4, 1, len(jpeg)), (50706, 1, 4, 0x00000401),
    ]
    ifd_size = 2 + len(entries) * 12 + 4
    bps = 8 + ifd_size
    make_at = bps + 8
    model_at = make_at + len(make) + len(make) % 2
    strip = model_at + len(model) + len(model) % 2
    offsets = {258: bps, 271: make_at, 272: model_at, 273: strip}
    ifd = struct.pack("<H", len(entries))
    for tag, kind, count, value in entries:
        ifd += struct.pack("<HHII", tag, kind, count, offsets[tag] if value is None else value)
    ifd += struct.pack("<I", 0)
    out = b"II*\x00" + struct.pack("<I", 8) + ifd + struct.pack("<3H", 8, 8, 8).ljust(8, b"\x00")
    out += make.ljust(len(make) + len(make) % 2, b"\x00") + model.ljust(len(model) + len(model) % 2, b"\x00")
    return out + jpeg


def exif_with_gps(image, lat, lon):
    exif = image.getexif()
    exif[0x010F] = "Smoke"          # Make
    exif[0x0110] = "Test camera"    # Model
    gps = exif.get_ifd(0x8825)
    gps[1] = "N" if lat >= 0 else "S"
    gps[2] = rational(abs(lat))
    gps[3] = "E" if lon >= 0 else "W"
    gps[4] = rational(abs(lon))
    return exif


for seed, (name, lat, lon) in enumerate(PHOTOS, start=1):
    image = picture(seed)
    exif = exif_with_gps(image, lat, lon)
    path = os.path.join(HERE, name)
    if name.endswith(".jpg"):
        image.save(path, "JPEG", quality=85, exif=exif)
    elif name.endswith(".png"):
        image.save(path, "PNG", exif=exif)
    elif name.endswith(".tif"):
        with open(path, "wb") as out:
            out.write(tiff_with_gps(image, lat, lon))
    else:
        image.save(path, "WEBP", quality=80, exif=exif)
    print(name, os.path.getsize(path), "bytes")

# raw.dng: the preview is a low-quality JPEG (small on purpose), 1024 px on its long side as RAW previews must be
preview = picture(5).resize((1024, 768))
import io
buffer = io.BytesIO()
preview.save(buffer, "JPEG", quality=25)
with open(os.path.join(HERE, "raw.dng"), "wb") as out:
    out.write(dng_with_preview(buffer.getvalue(), 1024, 768))
print("raw.dng", os.path.getsize(os.path.join(HERE, "raw.dng")), "bytes")
