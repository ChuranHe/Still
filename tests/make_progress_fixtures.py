from pathlib import Path
from PIL import Image
root=Path('build/progress-fixtures');root.mkdir(parents=True,exist_ok=True)
image=Image.effect_noise((2400,2000),90).convert('RGB');image.save(root/'large.jpg',quality=94)
source=(root/'large.jpg').read_bytes();folder=root/'local';folder.mkdir(exist_ok=True)
for i in range(24):(folder/f'photo-{i:02}.jpg').write_bytes(source+b'\nStill progress fixture '+str(i).encode())
(root/'corrupt.jpg').write_bytes(b'not a photo')
print('Created upload and 24-photo local-import fixtures:',len(source),'bytes per photo')
