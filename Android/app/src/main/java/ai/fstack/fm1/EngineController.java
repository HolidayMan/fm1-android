package ai.fstack.fm1;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** Serial native ownership, bounded pre-rendered PCM, and private durable storage. */
public final class EngineController {
 public static final String[] ENGINE_NAMES={"SLOOP 2.7.1","Felucca 1.6.1","X0X 1.1.0","Melodee 1.4.3","FM1 Open V13 Preview","Skiff 1.3.2","FoMni 0.4","Jangada 1.0.1"};
 public static final int[] ENGINE_KINDS={2,3,4,5,6,7,8,9};
 public volatile int selectedKind=2;
 public volatile boolean paused=false,muted=false;
 public volatile String status="Starting instrument…";
 public volatile int[] pixels=new int[57600];
 public volatile float[] leds=new float[41];
 public volatile long producedFrames,outputFrames,underflowFrames;
 public volatile double peak;
 public interface Listener {void onFrame();void onStatus();}
 private volatile Listener listener;
 private final Context context;
 private final File directory,projects;
 private final SharedPreferences prefs;
 private final Handler main=new Handler(Looper.getMainLooper()),worker;
 private final HandlerThread workerThread=new HandlerThread("FM1 native owner",Process.THREAD_PRIORITY_AUDIO);
 private final Object ringLock=new Object();
 private final float[] ring=new float[8192*2],renderBuffer=new float[1024];
 private int read,write,available;
 private volatile boolean closed,foreground=true,ready;
 private long machine,lastSnapshot,lastSave;
 private final int[] snapshotPixels=new int[57600];
 private final float[] snapshotLeds=new float[41];
 private volatile Thread exportThread;
 public final int audioBufferFrames;
 private int master;
 private final AudioTrack track;
 private final Thread audioThread;
 public EngineController(Context context){
  this.context=context.getApplicationContext();directory=new File(context.getFilesDir(),"Instruments");directory.mkdirs();projects=new File(context.getFilesDir(),"Projects");projects.mkdirs();prefs=context.getSharedPreferences("fm1",0);
  selectedKind=prefs.getInt("instrument",2);if(selectedKind<2||selectedKind>9)selectedKind=2;master=prefs.getInt("master",716);muted=prefs.getBoolean("muted",false);
  workerThread.start();worker=new Handler(workerThread.getLooper());
  int minimum=AudioTrack.getMinBufferSize(44100,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_FLOAT);
  track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build()).setBufferSizeInBytes(Math.max(minimum,1024*8)).setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
  track.setBufferSizeInFrames(Math.max(512,minimum/8));
  audioBufferFrames=track.getBufferSizeInFrames();
  audioThread=new Thread(this::audioLoop,"FM1 PCM output");audioThread.start();
  worker.post(()->{load(selectedKind);worker.post(tick);});
 }
 public void setListener(Listener l){listener=l;}
 private void notifyStatus(){main.post(()->{Listener l=listener;if(l!=null)l.onStatus();});}
 private void message(String s){status=s;notifyStatus();}
 private void failure(Exception e){Log.e("FM1", "Operation failed",e);message(e.getMessage()==null?"The operation could not be completed.":e.getMessage());}
 private void clearRing(){synchronized(ringLock){read=write=available=0;}}
 private boolean running(){return ready&&!paused&&foreground&&!closed;}
 private void audioLoop(){
  Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);float[] output=new float[1024];track.play();
  while(!closed){int n=0;boolean play=running();synchronized(ringLock){if(play){n=Math.min(512,available);for(int i=0;i<n*2;i++)output[i]=ring[(read*2+i)%ring.length];read=(read+n)%8192;available-=n;}}
   Arrays.fill(output,n*2,output.length,0);if(muted)Arrays.fill(output,0);if(play){underflowFrames+=512-n;outputFrames+=n;}
   int sent=track.write(output,0,output.length,AudioTrack.WRITE_BLOCKING);if(sent<0){Log.e("FM1","AudioTrack write "+sent);break;}
  }
  track.stop();track.release();
 }
 private final Runnable tick=new Runnable(){public void run(){
  if(closed)return;
  try{if(running()){
   int need;synchronized(ringLock){need=1024-available;}
   if(need>=512){int n=NativeEngine.render(machine,renderBuffer,512);if(n!=512)throw new IOException("The native instrument did not return complete audio.");
    for(float v:renderBuffer){if(!Float.isFinite(v))throw new IOException("The instrument returned invalid audio.");peak=Math.max(peak,Math.abs(v));}
    synchronized(ringLock){for(int i=0;i<1024;i++)ring[(write*2+i)%ring.length]=renderBuffer[i];write=(write+512)%8192;available+=512;}producedFrames+=512;
   }
   long now=android.os.SystemClock.uptimeMillis();if(now-lastSnapshot>=33){publishFrame();lastSnapshot=now;}if(now-lastSave>=10000){checkpoint();lastSave=now;}
  }}catch(Exception e){ready=false;clearRing();failure(e);}worker.postDelayed(this,2);
 }};
 private byte[] packageBytes(int kind)throws IOException {try(InputStream in=context.getAssets().open("engines/"+kind+".fwsc")){return readBounded(in,4*1024*1024);}}
 private File saveFile(int kind){return new File(directory,kind+(kind==6?".preview.flash":".flash"));}
 private void load(int kind){
  long candidate=0;boolean wasReady=ready;ready=false;clearRing();
  try{candidate=NativeEngine.create(packageBytes(kind),kind);File save=saveFile(kind);if(save.isFile()&&!NativeEngine.restore(candidate,Files.readAllBytes(save.toPath())))throw new IOException("Saved instrument data could not be restored. It has been kept.");NativeEngine.volume(candidate,master);
   if(machine!=0){NativeEngine.release(machine);checkpoint();NativeEngine.destroy(machine);}machine=candidate;candidate=0;selectedKind=kind;prefs.edit().putInt("instrument",kind).apply();clearRing();ready=true;peak=0;publishFrame();message("");
  }catch(Exception e){if(candidate!=0)NativeEngine.destroy(candidate);ready=wasReady;failure(e);}
 }
 private void publishFrame(){if(machine==0)return;NativeEngine.snapshot(machine,snapshotPixels,snapshotLeds);boolean changed=!Arrays.equals(pixels,snapshotPixels)||!Arrays.equals(leds,snapshotLeds);if(!changed)return;if(!Arrays.equals(pixels,snapshotPixels))pixels=snapshotPixels.clone();leds=snapshotLeds.clone();main.post(()->{Listener value=listener;if(value!=null)value.onFrame();});}
 private void checkpoint()throws IOException {if(machine==0)return;byte[] bytes=NativeEngine.flash(machine);if(bytes==null)throw new IOException("Could not save the instrument.");atomicWrite(saveFile(selectedKind),bytes);}
 public void selectEngine(int kind){if(kind<2||kind>9)return;message("Loading instrument…");worker.post(()->load(kind));}
 public void key(int id,boolean down){worker.post(()->{if(machine!=0&&running())NativeEngine.key(machine,id,down);});}
 public void encoder(int id,int delta){worker.post(()->{if(machine!=0)NativeEngine.encoder(machine,id,Math.max(-32,Math.min(32,delta)));});}
 public void setMaster(int value){master=Math.max(0,Math.min(1023,value));prefs.edit().putInt("master",master).apply();worker.post(()->{if(machine!=0)NativeEngine.volume(machine,master);});}
 public int getMaster(){return master;}
 public void releaseAll(){worker.post(()->{if(machine!=0)NativeEngine.release(machine);});}
 public void setPaused(boolean value){paused=value;worker.post(()->{clearRing();if(machine!=0){NativeEngine.release(machine);try{checkpoint();}catch(Exception e){failure(e);}}});notifyStatus();}
 public void setMuted(boolean value){muted=value;prefs.edit().putBoolean("muted",value).apply();notifyStatus();}
 public void setForeground(boolean value){foreground=value;worker.post(()->{clearRing();if(machine!=0){NativeEngine.release(machine);try{checkpoint();}catch(Exception e){failure(e);}}});}
 public String[] listProjects(){File[] files=projects.listFiles((d,n)->n.endsWith(".fm1project")||n.endsWith(".fm1backup"));if(files==null)return new String[0];Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));return Arrays.stream(files).map(File::getName).toArray(String[]::new);}
 public void saveProject(String name,boolean backup){worker.post(()->{try{requireSloop();ProjectSnapshot s=ProjectSnapshot.capture(machine,master);File f=new File(projects,safeName(name)+"-"+System.currentTimeMillis()+(backup?".fm1backup":".fm1project"));atomicWrite(f,s.envelope(name,backup));message(backup?"Workspace backup saved.":"Project copy saved.");}catch(Exception e){failure(e);}});}
 public void openProject(String filename){worker.post(()->{try{requireSloop();File file=projectFile(filename);applyProject(Files.readAllBytes(file.toPath()),filename);}catch(Exception e){failure(e);}});}
 public byte[] exportProject(String filename)throws IOException{return Files.readAllBytes(projectFile(filename).toPath());}
 public void importProject(byte[] bytes,String filename){worker.post(()->{try{requireSloop();ProjectSnapshot.fromEnvelope(bytes,filename);File target=new File(projects,UUID.randomUUID()+(filename.toLowerCase(Locale.ROOT).endsWith(".fm1backup")?".fm1backup":".fm1project"));atomicWrite(target,bytes);message("File imported. Open the saved copy when ready.");}catch(Exception e){failure(e);}});}
 private void applyProject(byte[] bytes,String filename)throws Exception {ProjectSnapshot next=ProjectSnapshot.fromEnvelope(bytes,filename);ProjectSnapshot current=ProjectSnapshot.capture(machine,master);if(!filename.toLowerCase(Locale.ROOT).endsWith(".fm1backup"))next=next.overlay(current);long candidate=next.create(packageBytes(2));try{atomicWrite(new File(projects,"Recovery-"+System.currentTimeMillis()+".fm1backup"),current.envelope("Recovery before opening",true));atomicWrite(saveFile(2),NativeEngine.flash(candidate));NativeEngine.release(machine);NativeEngine.destroy(machine);machine=candidate;candidate=0;master=next.master;prefs.edit().putInt("master",master).apply();clearRing();publishFrame();message("Opened as a copy. Previous work is in a recovery backup.");}finally{if(candidate!=0)NativeEngine.destroy(candidate);}}
 public void importSample(SampleAudio.Source source,double start,double end,boolean normalize,int root,int slot){worker.post(()->{try{requireSloop();ProjectSnapshot current=ProjectSnapshot.capture(machine,master),next=current.copy();next.parts.put("sample"+slot,SampleAudio.encode(source,start,end,normalize,root));long candidate=next.create(packageBytes(2));try{atomicWrite(new File(projects,"Recovery-"+System.currentTimeMillis()+".fm1backup"),current.envelope("Before sample import",true));atomicWrite(saveFile(2),NativeEngine.flash(candidate));NativeEngine.destroy(machine);machine=candidate;candidate=0;clearRing();publishFrame();message("Sample imported to USR"+(slot+1)+". Recovery backup saved.");}finally{if(candidate!=0)NativeEngine.destroy(candidate);}}catch(Exception e){failure(e);}});}
 public interface ExportCallback{void complete(File file,Exception error);}
 public void cancelExport(){Thread active=exportThread;if(active!=null)active.interrupt();}
 public void exportAudio(boolean wholeSong,int seconds,int tail,ExportCallback callback){worker.post(()->{try{requireSloop();if(exportThread!=null&&exportThread.isAlive())throw new IOException("Another audio export is still running.");ProjectSnapshot s=ProjectSnapshot.capture(machine,master);byte[] packageData=packageBytes(2);exportThread=new Thread(()->{try{File f=AudioExport.render(context,s,packageData,wholeSong,seconds,tail);main.post(()->callback.complete(f,null));}catch(Exception e){main.post(()->callback.complete(null,e));}},"FM1 offline render");exportThread.start();}catch(Exception e){main.post(()->callback.complete(null,e));}});}
 private void requireSloop()throws IOException{if(machine==0||selectedKind!=2)throw new IOException("Project files require SLOOP 2.7.1.");}
 private File projectFile(String name)throws IOException{File f=new File(projects,name);if(!f.getCanonicalFile().getParentFile().equals(projects.getCanonicalFile()))throw new IOException("Invalid project filename.");return f;}
 static String safeName(String s){String clean=s.trim().replaceAll("[^a-zA-Z0-9 _.-]","_");return clean.isEmpty()?"Project":clean.substring(0,Math.min(80,clean.length()));}
 static byte[] readBounded(InputStream in,int max)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("The file is too large.");out.write(b,0,n);}return out.toByteArray();}
 static void atomicWrite(File f,byte[] bytes)throws IOException{File tmp=new File(f.getPath()+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(bytes);out.getFD().sync();}if(!tmp.renameTo(f))throw new IOException("Could not finish saving the file.");}
 public void close(){foreground=false;cancelExport();worker.post(()->{try{checkpoint();}catch(Exception e){failure(e);}if(machine!=0){NativeEngine.destroy(machine);machine=0;}closed=true;workerThread.quitSafely();});}
}
