package ai.fstack.fm1;
import android.content.Context;
import java.io.*;
/** Offline disposable native engine: live state is never advanced by rendering. */
final class AudioExport {
 static File render(Context context,ProjectSnapshot snapshot,byte[] packageData,boolean song,int seconds,int tail)throws Exception{
  if(seconds<1||seconds>120||tail<0||tail>10)throw new IOException("Choose 1–120 seconds and 0–10 seconds of tail.");ProjectSnapshot copy=snapshot.copy();byte[] a=copy.parts.get("arrangement");
  if(song){int entries=a[10]&255;if(entries<1||entries>16||a.length!=11+entries*2)throw new IOException("Invalid song arrangement.");for(int i=0;i<entries;i++){int section=a[11+i*2]&255,bars=a[12+i*2]&255;if(section>3||bars<1||bars>64||(a[2]&(1<<section))==0)throw new IOException("Save every song section A–D in SLOOP before exporting.");}}
  a[3]=(byte)(song?1:0);a[9]=0;File folder=new File(context.getCacheDir(),"FM1Export-"+java.util.UUID.randomUUID());if(!folder.mkdir())throw new IOException("Could not create an audio export folder.");File file=new File(folder,song?"SLOOP-song.wav":"SLOOP-loop.wav");long machine=copy.create(packageData);boolean success=false;
  try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.setLength(0);out.write(SampleAudio.header(0,44100,2));float[] pcm=new float[1024];byte[] bytes=new byte[2048];NativeEngine.transport(machine,false);if(NativeEngine.render(machine,pcm,512)!=512)throw new IOException("Incomplete preparatory audio.");if(!NativeEngine.transport(machine,true))throw new IOException("Song transport unavailable.");int written=0,goal=seconds*44100;boolean started=false;
   while(true){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Audio export canceled.");int frames=song?128:Math.min(512,goal-written);write(out,machine,pcm,bytes,frames);written+=frames;if(song){byte[] state=NativeEngine.readPart(machine,1,0);if(state.length<13)throw new IOException("Could not read song position.");if(state[5]!=0)started=true;else if(started)break;else if(written>=44100)throw new IOException("The song did not start. Check saved sections and clock settings.");if(written>=30*60*44100)throw new IOException("Song export exceeded 30 minutes.");}else if(written>=goal)break;}
   NativeEngine.transport(machine,false);int left=tail*44100;while(left>0){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Audio export canceled.");int n=Math.min(512,left);write(out,machine,pcm,bytes,n);written+=n;left-=n;}out.seek(0);out.write(SampleAudio.header(written,44100,2));out.getFD().sync();success=true;return file;
  }finally{NativeEngine.destroy(machine);if(!success)file.delete();}
 }
 private static void write(RandomAccessFile out,long m,float[] pcm,byte[] bytes,int frames)throws IOException{if(NativeEngine.render(m,pcm,frames)!=frames)throw new IOException("The instrument could not render complete audio.");for(int i=0;i<frames*2;i++){if(!Float.isFinite(pcm[i]))throw new IOException("The instrument returned invalid audio.");SampleAudio.put16(bytes,i*2,(int)Math.max(-32768,Math.min(32767,pcm[i]*32768)));}out.write(bytes,0,frames*4);}
}
