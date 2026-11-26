# Generates crisp SaveGaze shield PNG icons (16, 32, 48, 128, 256) and SaveGaze.ico
from pathlib import Path
from PIL import Image, ImageDraw

OUT_EXT = Path(r"K:\SaveGaze-PC\extension\icons")
OUT_APP = Path(r"K:\SaveGaze-PC\src\SaveGaze.Desktop\assets")
OUT_EXT.mkdir(parents=True, exist_ok=True)
OUT_APP.mkdir(parents=True, exist_ok=True)


def draw_shield(size: int) -> Image.Image:
    # Supersample 4x for crisp antialiasing
    scale = 4
    s = size * scale
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # Background rounded badge
    pad = int(s * 0.06)
    d.rounded_rectangle(
        [pad, pad, s - pad, s - pad],
        radius=int(s * 0.22),
        fill=(15, 23, 42, 255),
        outline=(124, 58, 237, 255),
        width=max(2, int(s * 0.04)),
    )

    # Shield polygon
    cx = s * 0.5
    shield_pts = [
        (cx, s * 0.17),
        (s * 0.78, s * 0.27),
        (s * 0.75, s * 0.56),
        (cx, s * 0.84),
        (s * 0.25, s * 0.56),
        (s * 0.22, s * 0.27),
    ]
    d.polygon(shield_pts, fill=(109, 40, 217, 255), outline=(196, 181, 253, 255))

    # Inner eye / gaze-guard motif (horizontal arc + slash = SaveGaze)
    eye_rx = s * 0.17
    eye_ry = s * 0.10
    cy = s * 0.48
    d.ellipse(
        [cx - eye_rx, cy - eye_ry, cx + eye_rx, cy + eye_ry],
        fill=(248, 250, 252, 255),
    )
    pupil_r = s * 0.055
    d.ellipse(
        [cx - pupil_r, cy - pupil_r, cx + pupil_r, cy + pupil_r],
        fill=(15, 23, 42, 255),
    )
    # Protective diagonal bar
    d.line(
        [(s * 0.34, s * 0.62), (s * 0.66, s * 0.34)],
        fill=(244, 63, 94, 255),
        width=max(2, int(s * 0.065)),
    )

    return img.resize((size, size), Image.Resampling.LANCZOS)


for sz in (16, 48, 128):
    draw_shield(sz).save(OUT_EXT / f"{sz}.png", format="PNG")

ico_sizes = [(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
base = draw_shield(256)
base.save(OUT_APP / "SaveGaze.ico", format="ICO", sizes=ico_sizes)
draw_shield(128).save(OUT_APP / "SaveGaze-128.png", format="PNG")
print("Generated SaveGaze icons and SaveGaze.ico successfully.")
