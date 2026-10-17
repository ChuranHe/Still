from pathlib import Path
from PIL import Image,ImageDraw
root=Path('build/album-fixtures');root.mkdir(parents=True,exist_ok=True)
for name,size in [('square',(2000,2000)),('near-landscape',(1500,1200)),('near-portrait',(1200,1500)),('camera-landscape',(1600,1200)),('camera-portrait',(1200,1600)),('three-two',(1800,1200)),('two-three',(1200,1800)),('panorama',(3600,1200)),('tall',(1200,3600)),('wide',(1777,1000))]:
 image=Image.new('RGB',size,'#829990');draw=ImageDraw.Draw(image);draw.rectangle((0,0,size[0]//5,size[1]//5),fill='#ad6654');draw.rectangle((size[0]*4//5,size[1]*4//5,size[0],size[1]),fill='#dfca91');draw.rectangle((35,35,size[0]-36,size[1]-36),outline='#f4eddb',width=12);draw.text((size[0]//2-40,size[1]//2),name,fill='white');image.save(root/(name+'.jpg'),quality=92)
(root/'corrupt.jpg').write_bytes(b'not a photo')
print('Created all-ratio album fixtures')
