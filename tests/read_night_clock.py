"""Read the visible native night clock and its actual screen bounds via UIAutomator."""
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

adb=str(Path(__file__).resolve().parents[1]/'.tools/platform-tools/adb')
remote='/data/local/tmp/still-night-clock-ui.xml'
subprocess.run([adb,'shell','uiautomator','dump',remote],check=True,stdout=subprocess.DEVNULL,timeout=20)
root=ET.fromstring(subprocess.check_output([adb,'exec-out','cat',remote],timeout=10))
for node in root.iter('node'):
    text=node.get('text','')
    if node.get('class')=='android.widget.TextView' and not node.get('resource-id') and re.fullmatch(r'\d{1,2}:\d{2}(?: [AP]M)?',text):
        bounds=list(map(int,re.findall(r'\d+',node.get('bounds',''))))
        print(json.dumps({'text':text,'bounds':bounds,'center':[(bounds[0]+bounds[2])/2,(bounds[1]+bounds[3])/2]}))
        break
else:
    raise SystemExit('Visible native night clock not found')
