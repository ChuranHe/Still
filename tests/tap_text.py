"""Tap one native Android UI label, using UIAutomator bounds (test helper)."""
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

adb=str(Path(__file__).resolve().parents[1]/'.tools/platform-tools/adb')
remote='/data/local/tmp/still-v020-ui.xml'
subprocess.run([adb,'shell','uiautomator','dump',remote],check=True,stdout=subprocess.DEVNULL,timeout=20)
xml=subprocess.check_output([adb,'exec-out','cat',remote],timeout=10)
for node in ET.fromstring(xml).iter('node'):
    if node.get('text','').lstrip('▸ ').strip()==sys.argv[1]:
        a,b,c,d=map(int,re.findall(r'\d+',node.get('bounds')))
        subprocess.run([adb,'shell','input','tap',str((a+c)//2),str((b+d)//2)],check=True)
        break
else:
    raise SystemExit('Native UI label not found: '+sys.argv[1])
