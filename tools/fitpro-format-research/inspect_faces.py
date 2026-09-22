from pathlib import Path
import struct

def parse(b):
 assert b[:5]==bytes.fromhex('aa55010000')
 at=6; records=[]
 for g in range(b[5]):
  kind=b[at];at+=1
  n=b[at] if kind in (0,1) else 1
  if kind in (0,1):at+=1
  for i in range(n):
   bits=b[at];at+=1
   delay=b[at] if kind in (0,1) else 0
   if kind in (0,1):at+=1
   x,y,w,h=struct.unpack_from('<4H',b,at);at+=8
   count=b[at];at+=1
   offsets=struct.unpack_from('<'+'I'*count,b,at);at+=4*count
   records.append((kind,bits,delay,x,y,w,h,offsets))
 return at,records
if __name__=='__main__':
 for path in Path(__file__).parent.glob('anim*.bin'):
  b=path.read_bytes();at,rs=parse(b);ranges=set();print(path.name,'header',at,'first image',min(o for r in rs for o in r[-1]))
  for r in rs:
   kind,bits,delay,x,y,w,h,offsets=r;print('record',r[:-1], 'frames',len(offsets))
   for off in offsets:
    size=w*h*(bits//8)
    if bits==8:
     colors=struct.unpack_from('<H',b,off)[0];size+=2+2*colors
     assert 0<colors<=256
     assert max(b[off+2+2*colors:off+size])<colors
    assert off+size<=len(b)
    ranges.add((off,off+size))
  ranges=sorted(ranges);assert ranges[0][0]==at
  assert all(a[1]==c[0] for a,c in zip(ranges,ranges[1:])), ranges
  assert ranges[-1][1]==len(b)
  print('entire payload accounted for:',len(b),'bytes')
