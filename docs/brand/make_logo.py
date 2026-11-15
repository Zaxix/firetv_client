# Sharkfin mark: a fin cutting a wave, in a purple→blue gradient. Original design (paths below).
FIN = "M56 172C80 118 120 66 194 36C166 88 166 138 206 172Z"
# Wave as a filled ribbon: top crest line then back along a lower line.
WAVE = ("M22 196C40 182 58 182 76 196C94 210 112 210 130 196C148 182 166 182 184 196C202 210 220 210 234 198"
        "L234 214C220 226 202 226 184 212C166 198 148 198 130 212C112 226 94 226 76 212C58 198 40 198 22 212Z")
GRAD = ('<linearGradient id="g" x1="70" y1="36" x2="200" y2="226" gradientUnits="userSpaceOnUse">'
        '<stop offset="0" stop-color="#AA5CC3"/><stop offset="1" stop-color="#00A4DC"/></linearGradient>')
def mark(fill='url(#g)'):
    return f'<path d="{FIN}" fill="{fill}"/><path d="{WAVE}" fill="{fill}"/>'
def svg_mark(fill=None, size=256):
    defs = f'<defs>{GRAD}</defs>' if fill is None else ''
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 256 256">{defs}{mark(fill or "url(#g)")}</svg>'
BG = ('<linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#1B1F3B"/><stop offset="1" stop-color="#070914"/></linearGradient>'
      '<radialGradient id="glow" cx="0.62" cy="0.32" r="0.62"><stop offset="0" stop-color="#7A5BD6" stop-opacity="0.45"/><stop offset="0.6" stop-color="#0B7FC0" stop-opacity="0.12"/><stop offset="1" stop-color="#000" stop-opacity="0"/></radialGradient>')
def icon(size=512):
    s = size/256*0.78; off = size*(1-0.78)/2
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 {size} {size}"><defs>{GRAD}{BG}</defs>'
            f'<rect width="{size}" height="{size}" fill="url(#bg)"/><rect width="{size}" height="{size}" fill="url(#glow)"/>'
            f'<g transform="translate({off} {off-size*0.02}) scale({s})">{mark()}</g></svg>')
def banner(w=640, h=360):
    s = 0.92; 
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}"><defs>{GRAD}{BG}</defs>'
            f'<rect width="{w}" height="{h}" fill="url(#bg)"/><rect width="{w}" height="{h}" fill="url(#glow)"/>'
            f'<g transform="translate(36 52) scale({s})">{mark()}</g>'
            f'<text x="282" y="206" font-family="Inter" font-weight="800" font-size="84" letter-spacing="-2" fill="#FFFFFF">Sharkfin</text></svg>')
if __name__ == "__main__":
    open('icon.svg','w').write(icon()); open('banner.svg','w').write(banner())
    open('mark.svg','w').write(svg_mark()); open('mark-white.svg','w').write(svg_mark('#FFFFFF'))
