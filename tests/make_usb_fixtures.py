"""Create sparse, disposable filesystem images; never opens a physical disk."""
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1] / 'build/usb-fixtures'
ROOT.mkdir(parents=True, exist_ok=True)
EXPECTED = ROOT / 'expected'
EXPECTED.mkdir(exist_ok=True)
SECTOR = 512

def put(buf, offset, fmt, value):
    struct.pack_into('<' + fmt, buf, offset, value)

def payload(name, length, seed):
    value = bytes((i * 37 + seed) % 256 for i in range(length))
    (EXPECTED / name).write_bytes(value)
    return value

def mbr(start, length, kind):
    block = bytearray(SECTOR)
    block[450] = kind
    put(block, 454, 'I', start)
    put(block, 458, 'I', length)
    put(block, 510, 'H', 0xaa55)
    return block

def ex_entry(name, cluster, length, directory=False, contiguous=False, valid=None):
    encoded = name.encode('utf-16le')
    pieces = [encoded[i:i+30] for i in range(0, len(encoded), 30)]
    block = bytearray((2 + len(pieces)) * 32)
    block[0] = 0x85
    block[1] = 1 + len(pieces)
    put(block, 4, 'H', 0x10 if directory else 0x20)
    block[32] = 0xc0
    block[33] = 3 if contiguous else 1
    block[35] = len(encoded) // 2
    put(block, 40, 'Q', length if valid is None else valid)
    put(block, 52, 'I', cluster)
    put(block, 56, 'Q', length)
    for i, piece in enumerate(pieces):
        block[(i+2)*32] = 0xc1
        block[(i+2)*32+2:(i+2)*32+2+len(piece)] = piece
    checksum = 0
    for i, byte in enumerate(block):
        if i not in (2, 3):
            checksum = ((checksum >> 1) | ((checksum & 1) << 15)) + byte
            checksum &= 0xffff
    put(block, 2, 'H', checksum)
    return block

def exfat():
    start, sectors, heap, cluster_sectors = 2048, 131072, 64, 256
    cluster_bytes = SECTOR * cluster_sectors
    count = (sectors - heap) // cluster_sectors
    boot = bytearray(SECTOR * 12)
    boot[:3] = b'\xeb\x76\x90'
    boot[3:11] = b'EXFAT   '
    for offset, fmt, value in [(64,'Q',start),(72,'Q',sectors),(80,'I',24),(84,'I',16),
                                (88,'I',heap),(92,'I',count),(96,'I',2),(104,'H',0x100),(510,'H',0xaa55)]:
        put(boot, offset, fmt, value)
    boot[108:111] = bytes([9, 8, 1])
    checksum = 0
    for i, byte in enumerate(boot[:SECTOR*11]):
        if i not in (106, 107, 112):
            checksum = (((checksum & 1) << 31) | (checksum >> 1)) + byte
            checksum &= 0xffffffff
    for i in range(SECTOR*11, len(boot), 4): put(boot, i, 'I', checksum)
    fat = bytearray(SECTOR*16)
    for current, after in [(0,0xfffffff8),(1,0xffffffff),(2,0xffffffff),(4,8),(8,6),(6,0xffffffff),(30,30)]: put(fat,current*4,'I',after)
    label = bytearray(32);label[0:2] = bytes([0x83,7]);label[2:16] = 'USBTEST'.encode('utf-16le')
    landscape = payload('exfat-fragmented.bin', 280007, 19)
    portrait = payload('exfat-contiguous.bin', 150003, 72)
    directory = (label + ex_entry('Landscape long name.jpg',4,len(landscape)) +
                 ex_entry('Albums',3,cluster_bytes,True,True) + ex_entry('Cycle.jpg',30,cluster_bytes*2) +
                 ex_entry('Zero tail.jpg',22,1024,False,True,100) + ex_entry('Empty.jpg',0,0))
    sub = ex_entry('Portrait 京都.HEIC',20,len(portrait),False,True)
    path = ROOT / 'exfat-mbr.img'
    with path.open('wb') as image:
        image.truncate((start+sectors)*SECTOR)
        def write(offset, value): image.seek(offset);image.write(value)
        def cluster(number): return (start+heap+(number-2)*cluster_sectors)*SECTOR
        write(0,mbr(start,sectors,7));write(start*SECTOR,boot);write((start+24)*SECTOR,fat)
        write(cluster(2),directory);write(cluster(3),sub)
        for i, c in enumerate([4,8,6]): write(cluster(c),landscape[i*cluster_bytes:(i+1)*cluster_bytes])
        write(cluster(20),portrait);write(cluster(22),b'A'*1024);write(cluster(30),b'C'*cluster_bytes)
    return path

def fat_entry(alias, cluster, length, directory=False, name=None):
    short = bytearray(32);short[:11] = alias
    short[11] = 16 if directory else 32
    put(short,20,'H',cluster>>16);put(short,26,'H',cluster&65535);put(short,28,'I',length)
    if name is None: return short
    checksum = 0
    for c in alias: checksum = (((checksum&1)<<7)+(checksum>>1)+c)&255
    chars = [ord(c) for c in name] + [0]
    while len(chars)%13: chars.append(0xffff)
    entries = []
    for i in range(len(chars)//13):
        entry = bytearray(32);entry[0] = i+1;entry[11] = 15;entry[13] = checksum
        for offset, char in zip([1,3,5,7,9,14,16,18,20,22,24,28,30],chars[i*13:(i+1)*13]): put(entry,offset,'H',char)
        entries.append(entry)
    entries[-1][0] |= 64
    return b''.join(reversed(entries)) + short

def fat32():
    start,sectors,reserved,fat_sectors = 2048,131072,32,1024
    heap = reserved + fat_sectors*2
    boot = bytearray(512);boot[:3] = b'\xeb\x58\x90';boot[3:11] = b'MSWIN4.1';boot[82:90] = b'FAT32   '
    for p,fmt,v in [(11,'H',512),(14,'H',reserved),(32,'I',sectors),(36,'I',fat_sectors),(44,'I',2),(510,'H',0xaa55)]:put(boot,p,fmt,v)
    boot[13] = 1;boot[16] = 2
    fat = bytearray(fat_sectors*512)
    chain = [4,7,5,9,10,11,13,14]
    for c,n in [(0,0x0ffffff8),(1,0x0fffffff),(2,0x0fffffff),(12,0x0fffffff),*zip(chain,chain[1:]+[0x0fffffff])]:put(fat,c*4,'I',n)
    content = payload('fat32-lfn.bin',4000,31)
    root = fat_entry(b'TRAVEL~1JPE',4,len(content),name='旅行 Portrait.jpeg') + fat_entry(b'PHOTOS     ',12,0,True) + fat_entry(b'EMPTY   JPG',0,0)
    sub = fat_entry(b'OTHER   JPG',4,len(content))
    path = ROOT / 'fat32-mbr.img'
    with path.open('wb') as image:
        image.truncate((start+sectors)*512)
        def write(offset,value):image.seek(offset);image.write(value)
        def cluster(c):return (start+heap+c-2)*512
        write(0,mbr(start,sectors,12));write(start*512,boot);write((start+reserved)*512,fat);write((start+reserved+fat_sectors)*512,fat)
        write(cluster(2),root);write(cluster(12),sub)
        for i,c in enumerate(chain):write(cluster(c),content[i*512:(i+1)*512])
    # Same filesystem under GPT. CRCs protect the header and partition entries.
    gpt = ROOT / 'fat32-gpt.img'
    with path.open('rb') as src, gpt.open('wb') as dst:
        dst.truncate((start+sectors+2048)*512)
        src.seek(start*512);dst.seek(start*512)
        while chunk := src.read(1024*1024):dst.write(chunk)
        dst.seek(0);dst.write(mbr(1,start+sectors+2047,0xee))
        entries = bytearray(128*128);entries[:16] = bytes(range(1,17));put(entries,32,'Q',start);put(entries,40,'Q',start+sectors-1)
        header = bytearray(512);header[:8] = b'EFI PART'
        for p,fmt,v in [(8,'I',0x10000),(12,'I',92),(24,'Q',1),(32,'Q',start+sectors+2047),(40,'Q',34),(48,'Q',start+sectors+2014),(72,'Q',2),(80,'I',128),(84,'I',128),(88,'I',zlib.crc32(entries))]:put(header,p,fmt,v)
        put(header,16,'I',zlib.crc32(header[:92]));dst.seek(512);dst.write(header);dst.seek(1024);dst.write(entries)

if __name__ == '__main__':
    exfat();fat32();print('Created disposable FAT32/exFAT/MBR/GPT images in',ROOT)
