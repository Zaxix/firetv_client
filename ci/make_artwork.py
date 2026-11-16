"""Original, generated artwork + NFO metadata for the CI test library (no third-party images)."""
import os, sys, random, colorsys, html
from PIL import Image, ImageDraw, ImageFilter, ImageFont

root = sys.argv[1]
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_REG = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

MOVIES = {
    "Harbor Lights (2021)": dict(hue=0.58, year=2021, genres=["Drama", "Mystery"], rating=7.4, mpaa="PG-13",
        plot="A night-shift harbor pilot notices the lighthouse signals have started spelling words — in a language no one has spoken for a century.",
        tagline="Some lights are meant to be read."),
    "Tidewater (2017)": dict(hue=0.55, year=2017, genres=["Drama", "Thriller"], rating=7.2, mpaa="PG-13",
        plot="A coastal ferry captain keeps a logbook of every crossing, until the entries start describing tomorrow's.",
        tagline="Every tide comes back."),
    "Glass Meridian (2023)": dict(hue=0.80, year=2023, genres=["Science Fiction", "Thriller"], rating=8.1, mpaa="PG-13",
        plot="An orbital cartographer must map a city that rebuilds itself every sunrise before the station's last shuttle departs.",
        tagline="The map is never the territory."),
    "Seven Rivers (2019)": dict(hue=0.42, year=2019, genres=["Adventure", "Family"], rating=6.9, mpaa="PG",
        plot="Three siblings follow their late grandmother's handwritten river guide across seven watersheds, one summer at a time.",
        tagline="Every river remembers."),
    "Ember Field (2024)": dict(hue=0.04, year=2024, genres=["Action", "Drama"], rating=7.8, mpaa="R",
        plot="A wildfire crew spends one impossible night holding the line between a valley town and a fire that seems to think.",
        tagline="Hold the line."),
    "Old Reel (1999)": dict(hue=0.12, year=1999, genres=["Comedy"], rating=6.2, mpaa="PG",
        plot="A projectionist inherits a cinema with a single film reel that changes ending every time it is shown.",
        tagline="Same film. New ending."),
    # Dolby Vision samples (only present when CI could build them).
    "Prism Coast (2025)": dict(hue=0.66, year=2025, genres=["Documentary"], rating=7.1, mpaa="PG",
        plot="A light-sculptor walks the length of a coastline where the sea refracts sunrise into colours no camera has recorded.",
        tagline="Dolby Vision profile 8.1."),
    "Neon Tide (2025)": dict(hue=0.90, year=2025, genres=["Music"], rating=6.8, mpaa="PG",
        plot="A synth band records an album live on a night ferry, one track per harbour, in colours that only exist after dark.",
        tagline="Dolby Vision profile 5."),
    "Velvet Static (2025)": dict(hue=0.72, year=2025, genres=["Thriller"], rating=7.6, mpaa="PG-13",
        plot="A broadcast engineer finds a second picture hidden underneath the first in every signal her station receives.",
        tagline="Dolby Vision profile 7."),
}
STUDIO = {'Harbor Lights (2021)': 'Apple TV+', 'Glass Meridian (2023)': 'Netflix', 'Seven Rivers (2019)': 'Paramount Pictures', 'Ember Field (2024)': 'A24', 'Old Reel (1999)': 'Walt Disney Pictures', 'Prism Coast (2025)': 'National Geographic', 'Neon Tide (2025)': 'Hulu', 'Velvet Static (2025)': 'HBO'}  # synthetic studio credits so brand tiles have something to show
CRITIC = {'Harbor Lights (2021)': 81, 'Glass Meridian (2023)': 93, 'Seven Rivers (2019)': 72, 'Ember Field (2024)': 54, 'Old Reel (1999)': 64, 'Prism Coast (2025)': 88, 'Neon Tide (2025)': 67, 'Velvet Static (2025)': 90}  # synthetic "critics" scores so the ratings strip has data
SERIES = dict(name="Skylark Isles", hue=0.50, year=2022, genres=["Animation", "Fantasy"], rating=8.6,
    plot="Kira, a sky-cartographer apprentice, chases the aurora across a floating archipelago to find where the lights are born.",
    episodes=["The Archipelago Wakes", "A Map Made of Wind", "Where the Lights Are Born"])

def gradient(w, h, hue, seed):
    rnd = random.Random(seed)
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / h
        r, g, b = colorsys.hls_to_rgb((hue + 0.08 * t) % 1, 0.10 + 0.22 * (1 - t), 0.55)
        d.line([(0, y), (w, y)], fill=(int(r * 255), int(g * 255), int(b * 255)))
    glow = Image.new("RGB", (w, h), (0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for _ in range(7):
        cx, cy, rad = rnd.uniform(0, w), rnd.uniform(0, h * 0.8), rnd.uniform(0.15, 0.45) * max(w, h)
        r, g, b = colorsys.hls_to_rgb((hue + rnd.uniform(-0.12, 0.12)) % 1, 0.55, 0.8)
        gd.ellipse([cx - rad, cy - rad * 0.4, cx + rad, cy + rad * 0.4], fill=(int(r * 255), int(g * 255), int(b * 255)))
    glow = glow.filter(ImageFilter.GaussianBlur(max(w, h) * 0.08))
    return Image.blend(img, glow, 0.45)

def text_center(img, text, size, y, font=FONT, fill=(245, 246, 250)):
    d = ImageDraw.Draw(img)
    f = ImageFont.truetype(font, size)
    while d.textlength(text, font=f) > img.width * 0.88 and size > 10:
        size -= 2; f = ImageFont.truetype(font, size)
    d.text(((img.width - d.textlength(text, font=f)) / 2, y), text, font=f, fill=fill)

def logo(title, path):
    f = ImageFont.truetype(FONT, 120)
    w = int(ImageDraw.Draw(Image.new("RGBA", (1, 1))).textlength(title.upper(), font=f)) + 40
    img = Image.new("RGBA", (w, 170), (0, 0, 0, 0))
    ImageDraw.Draw(img).text((20, 10), title.upper(), font=f, fill=(255, 255, 255, 255))
    img.save(path)

def art(folder, title, hue, seed):
    poster = gradient(680, 1000, hue, seed)
    text_center(poster, title.upper(), 64, 760)
    poster.save(os.path.join(folder, "poster.jpg"), quality=90)
    backdrop = gradient(1920, 1080, hue + 0.03, seed + 1)
    backdrop.save(os.path.join(folder, "backdrop.jpg"), quality=88)
    logo(title, os.path.join(folder, "logo.png"))

def esc(s): return html.escape(s)

for folder, m in MOVIES.items():
    path = os.path.join(root, "movies", folder)
    if not os.path.isdir(path):
        continue
    title = folder.rsplit(" (", 1)[0]
    art(path, title, m["hue"], hash(folder) & 0xffff)
    genres = "".join(f"<genre>{g}</genre>" for g in m["genres"])
    open(os.path.join(path, os.path.basename(folder) + ".nfo"), "w").write(
        f"<?xml version='1.0' encoding='utf-8'?><movie><title>{esc(title)}</title><year>{m['year']}</year>"
        f"<plot>{esc(m['plot'])}</plot><tagline>{esc(m['tagline'])}</tagline><rating>{m['rating']}</rating>"
        f"<criticrating>{CRITIC.get(folder, 70)}</criticrating><studio>{esc(STUDIO.get(folder, 'Aurora Test Pictures'))}</studio><mpaa>{m['mpaa']}</mpaa>{genres}<premiered>{m['year']}-05-01</premiered>"
        f"<actor><name>Ada Linden</name><role>Lead</role></actor><actor><name>Marco Vale</name><role>Support</role></actor></movie>")

s = SERIES
spath = os.path.join(root, "tv", s["name"])
art(spath, s["name"], s["hue"], 42)
open(os.path.join(spath, "tvshow.nfo"), "w").write(
    f"<?xml version='1.0' encoding='utf-8'?><tvshow><title>{s['name']}</title><studio>Crunchyroll</studio><year>{s['year']}</year><plot>{esc(s['plot'])}</plot>"
    f"<rating>{s['rating']}</rating>" + "".join(f"<genre>{g}</genre>" for g in s["genres"]) + "<status>Continuing</status></tvshow>")
season = os.path.join(spath, "Season 01")
for i, name in enumerate(s["episodes"], 1):
    base = os.path.join(season, f"{s['name']} - S01E0{i}")
    thumb = gradient(1280, 720, s["hue"] + i * 0.05, 100 + i)
    text_center(thumb, f"EPISODE {i}", 72, 300)
    thumb.save(base + "-thumb.jpg", quality=88)
    open(base + ".nfo", "w").write(
        f"<?xml version='1.0' encoding='utf-8'?><episodedetails><title>{esc(name)}</title><season>1</season><episode>{i}</episode>"
        f"<plot>Episode {i} of the Skylark Isles test series, with complex ASS signs, karaoke and overlapping dialogue.</plot>"
        f"<aired>2022-0{i}-01</aired></episodedetails>")
print("artwork + nfo written")
