"""Optional artwork regeneration: pip install resvg-py. Not required for builds."""
from pathlib import Path
import struct
import xml.etree.ElementTree as ET
import resvg_py

root = Path(__file__).resolve().parent.parent
assets = root / "assets"
logo = ET.parse(assets / "sqlite-logo.svg")
feather = next(element.attrib["d"] for element in logo.iter() if element.attrib.get("id") == "path55")
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" viewBox="0 0 256 256">
  <title>TTWR SQLite</title>
  <desc>Connected TTWR nodes with the feather from SQLite's official logo.</desc>
  <g fill="#12cddd" stroke="#12cddd" stroke-width="18" stroke-linecap="round">
    <path d="M38 130 L120 65 M38 130 L120 198" fill="none"/>
    <circle cx="38" cy="130" r="21" stroke="none"/>
    <circle cx="120" cy="65" r="23" stroke="none"/>
    <circle cx="120" cy="198" r="23" stroke="none"/>
  </g>
  <g transform="translate(143 24) scale(4.45) translate(-76 -62)">
    <path d="{feather}" fill="#0078b5"/>
  </g>
</svg>
'''
(assets / "ttwr-sqlite.svg").write_text(svg, encoding="utf-8", newline="\n")
sizes = [16, 24, 32, 48, 64, 128, 256]
images = [resvg_py.svg_to_bytes(svg_string=svg, width=size, height=size) for size in sizes]
(assets / "ttwr-sqlite.png").write_bytes(images[-1])
offset = 6 + 16 * len(sizes)
entries = []
for size, image in zip(sizes, images):
    entries.append(struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32, len(image), offset))
    offset += len(image)
(assets / "ttwr-sqlite.ico").write_bytes(struct.pack("<HHH", 0, 1, len(sizes)) + b"".join(entries) + b"".join(images))
print("Generated SVG, PNG and ICO (16–256 px)")
