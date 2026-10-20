package ai.fstack.fm1;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.CRC32;

/** WAV decode/trim and FSMP IMA-ADPCM port; derived from the attributed iOS codec. */
public final class SampleAudio {
 public static final class Source {public final String name;public final float[] samples;Source(String name,float[] samples){this.name=name;this.samples=samples;}public double duration(){return samples.length/22050.0;}}
 private static IOException error(String message){return new IOException(message);}
 private static int u16(byte[] b,int p){return (b[p]&255)|((b[p+1]&255)<<8);}
 private static int i32(byte[] b,int p){return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|(b[p+3]<<24);}
 private static boolean tag(byte[] b,int p,String s){for(int i=0;i<4;i++)if(b[p+i]!=(byte)s.charAt(i))return false;return true;}
 public static Source decode(byte[] b,String name)throws IOException {
  if(b.length<44||b.length>32*1024*1024||!tag(b,0,"RIFF")||!tag(b,8,"WAVE"))throw error("Choose a valid WAV file no larger than 32 MB.");int format=0,channels=0,rate=0,bits=0,align=0,data=-1,count=0;
  for(int p=12;p+8<=b.length;){long size=Integer.toUnsignedLong(i32(b,p+4));long end=(long)p+8+size;if(end>b.length)throw error("The WAV file is incomplete.");if(tag(b,p,"fmt ")){if(size<16)throw error("Invalid WAV format.");format=u16(b,p+8);channels=u16(b,p+10);rate=i32(b,p+12);align=u16(b,p+20);bits=u16(b,p+22);}if(tag(b,p,"data")){data=p+8;count=(int)size;}p=(int)(end+(size&1));}
  if(data<0||channels<1||channels>2||rate<8000||rate>192000||count<1||!((format==1&&(bits==8||bits==16||bits==24||bits==32))||(format==3&&bits==32))||align!=channels*bits/8||count%align!=0)throw error("Use mono/stereo PCM or float WAV, 8–192 kHz.");int frames=count/align;if(frames/(double)rate>120)throw error("Use a WAV up to two minutes long.");float[] source=new float[frames];
  for(int i=0;i<frames;i++){double sum=0;for(int c=0;c<channels;c++){int p=data+i*align+c*(bits/8);double v;if(format==3)v=Float.intBitsToFloat(i32(b,p));else if(bits==8)v=((b[p]&255)-128)/128.0;else if(bits==16)v=(short)u16(b,p)/32768.0;else if(bits==24)v=((b[p]&255)<<8|(b[p+1]&255)<<16|b[p+2]<<24)/2147483648.0;else v=i32(b,p)/2147483648.0;if(!Double.isFinite(v))throw error("The WAV contains invalid audio.");sum+=v;}source[i]=(float)Math.max(-1,Math.min(1,sum/channels));}
  int n=Math.max(1,(int)Math.ceil(frames*22050.0/rate));float[] samples=new float[n];
  // Windowed-sinc low-pass resampling avoids aliasing when downsampling.
  double cutoff=Math.min(1,22050.0/rate)*0.94;for(int i=0;i<n;i++){double x=i*(double)rate/22050;int center=(int)x;double sum=0,weight=0;for(int j=-24;j<=24;j++){int index=Math.max(0,Math.min(frames-1,center+j));double t=(center+j-x)*cutoff;double sinc=Math.abs(t)<1e-9?1:Math.sin(Math.PI*t)/(Math.PI*t);double window=0.5+0.5*Math.cos(Math.PI*(center+j-x)/25);double w=sinc*window*cutoff;sum+=source[index]*w;weight+=w;}samples[i]=(float)Math.max(-1,Math.min(1,sum/weight));}
  return new Source(name,samples);
 }
 private static int[] region(Source source,double start,double end)throws IOException{if(!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<=start||end>source.duration()+0.00001)throw error("Choose a valid start and end within the sound.");return new int[]{(int)Math.floor(start*22050),Math.min(source.samples.length,(int)Math.ceil(end*22050))};}
 public static byte[] encode(Source source,double start,double end,boolean normalize,int root)throws IOException{
  int[] r=region(source,start,end);int n=r[1]-r[0];if(n<1||n>162816||root<0||root>127)throw error("Trim the sound to fit the 80 KiB sample slot (7.38 seconds).");float peak=0;for(int i=r[0];i<r[1];i++)peak=Math.max(peak,Math.abs(source.samples[i]));double gain=normalize&&peak>0?30000/(32768.0*peak):1;byte[] encoded=new byte[(n+1)/2];int predictor=0,index=0;
  for(int i=0;i<n;i++){int sample=(int)Math.max(-32768,Math.min(32767,source.samples[r[0]+i]*gain*32768));int step=STEPS[index],diff=sample-predictor,code=0,delta=step>>3;if(diff<0){code=8;diff=-diff;}if(diff>=step){code|=4;diff-=step;delta+=step;}if(diff>=step>>1){code|=2;diff-=step>>1;delta+=step>>1;}if(diff>=step>>2){code|=1;delta+=step>>2;}predictor=Math.max(-32768,Math.min(32767,predictor+((code&8)!=0?-delta:delta)));index=Math.max(0,Math.min(88,index+ADJUST[code&7]));if((i&1)==0)encoded[i/2]=(byte)code;else encoded[i/2]|=(byte)(code<<4);}
  byte[] slot=new byte[81920];Arrays.fill(slot,(byte)255);Arrays.fill(slot,0,512,(byte)0);put32(slot,0,0x504d5346);put16(slot,4,1);slot[6]=1;String title=source.name.toUpperCase(Locale.ROOT).replaceAll("[^ -~]","");for(int i=0;i<Math.min(8,title.length());i++)slot[8+i]=(byte)title.charAt(i);put32(slot,16,encoded.length);CRC32 crc=new CRC32();crc.update(encoded);put32(slot,20,(int)crc.getValue());put32(slot,36,n);put32(slot,44,n-1);put32(slot,48,32768);put16(slot,52,root*16);slot[58]=127;System.arraycopy(encoded,0,slot,512,encoded.length);return slot;
 }
 public static byte[] preview(Source source,double start,double end)throws IOException{int[] r=region(source,start,end);byte[] pcm=new byte[(r[1]-r[0])*2];for(int i=r[0];i<r[1];i++)put16(pcm,(i-r[0])*2,(int)Math.max(-32768,Math.min(32767,source.samples[i]*32768)));byte[] result=new byte[44+pcm.length];System.arraycopy(header(pcm.length/2,22050,1),0,result,0,44);System.arraycopy(pcm,0,result,44,pcm.length);return result;}
 static byte[] header(int frames,int rate,int channels){byte[] b=new byte[44];for(int i=0;i<4;i++){b[i]=(byte)"RIFF".charAt(i);b[8+i]=(byte)"WAVE".charAt(i);b[12+i]=(byte)"fmt ".charAt(i);b[36+i]=(byte)"data".charAt(i);}put32(b,4,frames*channels*2+36);put32(b,16,16);put16(b,20,1);put16(b,22,channels);put32(b,24,rate);put32(b,28,rate*channels*2);put16(b,32,channels*2);put16(b,34,16);put32(b,40,frames*channels*2);return b;}
 static void put16(byte[] b,int p,int value){b[p]=(byte)value;b[p+1]=(byte)(value>>8);}
 static void put32(byte[] b,int p,int value){for(int i=0;i<4;i++)b[p+i]=(byte)(value>>(i*8));}
 private static final int[] ADJUST={-1,-1,-1,-1,2,4,6,8};
 private static final int[] STEPS={7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,88,97,107,118,130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,724,796,876,963,1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,2749,3024,3327,3660,4026,4428,4871,5358,5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,15289,16818,18500,20350,22385,24623,27086,29794,32767};
}
