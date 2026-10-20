package ai.fstack.fm1;
/** All operations on a handle belong to its owning serial worker. */
final class NativeEngine {
 static {System.loadLibrary("fm1android");}
 static native long create(byte[] packageBytes,int requiredKind);
 static native void destroy(long handle);
 static native int kind(long handle);
 static native int render(long handle,float[] pcm,int frames);
 static native void key(long handle,int id,boolean down);
 static native void release(long handle);
 static native void encoder(long handle,int id,int delta);
 static native void volume(long handle,int value);
 static native int snapshot(long handle,int[] pixels,float[] leds);
 static native byte[] flash(long handle);
 static native boolean restore(long handle,byte[] flash);
 static native byte[] readPart(long handle,int type,int id);
 static native int writePart(long handle,int type,int id,byte[] bytes);
 static native boolean transport(long handle,boolean playing);
 static native int commit(long handle);
}
