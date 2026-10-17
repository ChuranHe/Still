"""Generate disposable JPEG and genuine HEVC-encoded HEIF fixtures on macOS."""
from pathlib import Path
from PIL import Image, ImageDraw
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/orientation-fixtures'
OUT.mkdir(parents=True, exist_ok=True)
PREFIX = 'still-v020-'

def photo(name, size, color, orientation=1):
    image=Image.new('RGB',size,color)
    draw=ImageDraw.Draw(image); w,h=size
    draw.rectangle((0,0,w,h//8),fill='#ce6956')
    draw.rectangle((0,7*h//8,w,h),fill='#5178ad')
    draw.rectangle((0,0,w//8,h),fill='#cfb64f')
    draw.ellipse((w*.45,h*.25,w*.75,h*.25+w*.3),fill='#e9d5a8')
    draw.polygon([(w*.1,h*.85),(w*.55,h*.43),(w*.9,h*.85)],fill='#315b44')
    draw.text((w*.16,h*.15),'DISPOSABLE TEST / '+name,fill='#f5ecd2',font_size=22)
    exif=Image.Exif();exif[274]=orientation
    path=OUT/(PREFIX+name);image.save(path,format='JPEG',quality=94,exif=exif)
    return path

a=photo('a-landscape.jpg',(1280,800),'#92ac9a')
photo('b-landscape-4x3.jpeg',(1600,1200),'#9baa91')
photo('c-portrait.jpg',(800,1280),'#a9948c')
d=photo('d-portrait-3x4.JPEG',(1200,1600),'#8caaa4')
e=photo('e-exif-portrait.jpg',(1280,800),'#aeaa8c',6)
photo('f-mirrored-landscape.jpg',(800,1280),'#9b8eac',5)
for source,name in [(a,'g-landscape.heic'),(d,'h-portrait.heif'),(e,'i-rotated.heic')]:
    # The .heif fixture has a normal HEIC/HEVC HEIF container, as used by iPhones.
    target=OUT/(PREFIX+name)
    subprocess.run(['/usr/bin/sips','-s','format','heic',str(source),'--out',str(target)],check=True,stdout=subprocess.DEVNULL)
    header=target.read_bytes()[:32]
    assert b'ftyp' in header and any(brand in header for brand in [b'heic',b'heix',b'mif1']),header
photo('j-wrong-ratio.jpg',(1920,1080),'#a6928c')
photo('k-square.jpg',(1000,1000),'#938ca6')
(OUT/(PREFIX+'l-corrupt.heif')).write_bytes(b'Not a HEIF image')
shutil.copyfile(a,OUT/(PREFIX+'m-duplicate.JPEG'))
print(OUT)
