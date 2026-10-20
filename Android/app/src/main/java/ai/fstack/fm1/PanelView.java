package ai.fstack.fm1;

import android.content.Context;
import android.graphics.*;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;

/** Native multitouch instrument face. Engine LEDs never derive from held touches. */
public final class PanelView extends View {
    public interface Actions { void key(int id,boolean down); void encoder(int id,int steps); void master(int value); void enlarge(); }
    public static final String[] FINISH_NAMES={"Black","Purple","Orange","Black-Green","Cool Gray","White-Blue"};
    // body, bodyBottom, accent, control, controlBottom, ink, controlInk, LED, recess
    public static final int[][] PALETTES={
        {0x3b3c3c,0x343535,0x555757,0x303231,0x272928,0xe5e6e3,0xf0f1eb,0xbaffad,0x18251c},
        {0xb88fc9,0xae82bf,0x9a79ae,0x8178b7,0x7b72af,0x211728,0x06060c,0xe9caff,0x473962},
        {0xbf704c,0xb36746,0xa75f43,0xa06959,0x915c4f,0x0c0806,0xffffff,0xffd59c,0x4c3026},
        {0x3b3c3c,0x343535,0x555757,0x7ccdc4,0x69bbb2,0xe5e6e3,0x203d3c,0xa6fff0,0x244c48},
        {0xdfd8d0,0xd5cdc3,0xc6beb5,0x444c4e,0x363e40,0x252a2b,0xeef4f2,0xe2edff,0x293a46},
        {0xeeece4,0xe3e0d7,0xd2d0c8,0x52758e,0x42637c,0x2a343a,0xfffbf5,0xa7ddff,0x294052}};
    private static final String[] LABELS={"FX","SEL","ENV","LFO","EDIT","GLO","HOME","SAVE","ARP","SEQ","PLAY\nSTOP","REC"};
    private static final int[] WHITES={0,2,4,6,7,9,11,12,14,16,18,19,21,23,24,26};
    private static final int[][] BLACKS={{1,0},{3,1},{5,2},{8,4},{10,5},{13,7},{15,8},{17,9},{20,11},{22,12},{25,14}};
    private static final class Control {RectF r;String label;int type,id; Control(String l,float x,float y,float w,float h,int t,int i){label=l;r=new RectF(x,y,x+w,y+h);type=t;id=i;}}
    private static final class Touch {int control;float y; Touch(int c,float y){control=c;this.y=y;}}
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Control> controls=new ArrayList<>();
    private final SparseArray<Touch> touches=new SparseArray<>();
    private final int[] held=new int[41];
    private final float[] angles=new float[8];
    private final Bitmap screen=Bitmap.createBitmap(240,240,Bitmap.Config.ARGB_8888);
    private Bitmap artwork;
    private float scale=1,referenceHeight,keyboardTop;
    private int finish=0,master=716;
    private float[] leds=new float[41];
    private boolean screenReady;
    private Actions actions;
    public PanelView(Context context){super(context);setContentDescription("FM–1 instrument panel, 27 keys");setFocusable(true);}
    public void setActions(Actions value){actions=value;}
    public void setMasterIndicator(int value){master=Math.max(0,Math.min(1023,value));invalidate();}
    public void setFinish(int value){finish=Math.max(0,Math.min(5,value));rebuild();}
    public void setReferenceHeight(float pixels){referenceHeight=pixels;rebuild();}
    public void updateFrame(int[] pixels,float[] levels){if(pixels!=null&&pixels.length==57600){screen.setPixels(pixels,0,240,0,0,240,240);screenReady=true;} if(levels!=null)leds=levels.clone();invalidate();}
    private void add(String l,float x,float y,float w,float h,int t,int id){controls.add(new Control(l,x,y,w,h,t,id));}
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){super.onSizeChanged(w,h,ow,oh);releaseTouches();rebuild();}
    private void rebuild(){if(getWidth()==0||getHeight()==0)return;scale=getResources().getDisplayMetrics().density;float w=getWidth()/scale,h=getHeight()/scale;
        float kh=Math.min(240,Math.max(118,h*.42f)),u=h-kh-8; if(u<110)return;
        float left=w*.235f,slot=(left-22)/2,ref=referenceHeight>0?Math.min(h,referenceHeight/scale):h;
        float ru=ref-Math.min(240,Math.max(118,ref*.42f))-8;if(ru<110)ru=u;
        float rs=Math.min(w*.29f,Math.min(Math.max(w*.23f,ru-4),Math.max(w*.23f,w-left-24-(6*44+25))));
        float rr=w-left-rs-24,k=Math.min(90,Math.min(slot,Math.min((ru-88)/2,Math.min((rr-30)/4,ru*.36f))));k=Math.max(24,k);
        float sw=Math.min(Math.max(w*.23f,u-4),Math.max(0,w-left-24-Math.max(6*40+25,4*k+30)));
        float rx=left+sw+12,rw=w-rx-12,gap=Math.max(0,Math.min(slot+4-k,ru-63-2*k)),cluster=2*k+gap;
        float x=(left-cluster)/2,y=(19+u-44-cluster)/2,ft=Math.max(k+26,ru*.51f),offset=u-ru,ky=(ft-k+15)/2+offset;
        controls.clear();add("MASTER",x,y,k,k,2,7);add("SELECT",x+k+gap,y,k,k,1,0);add("PRESETS",x,y+k+gap,k,k,1,6);add("ALGORITHM",x+k+gap,y+k+gap,k,k,1,1);
        add("OCT−",9,u-39,(left-22)/2,36,0,0);add("OCT+",13+(left-22)/2,u-39,(left-22)/2,36,0,1);
        float ss=Math.min(sw-8,u-16);add("Enlarge display",left+4+(sw-8-ss)/2,12,ss,ss,3,0);
        for(int i=0;i<4;i++)add("KNOB"+(i+1),rx+rw/4*i+(rw/4-k)/2,ky,k,k,1,i+2);
        float bw=(rw-25)/6,bh=(ru-ft-8)/2;for(int i=0;i<12;i++)add(LABELS[i],rx+i%6*(bw+5),ft+offset+i/6*(bh+5),bw,bh,0,i+2);
        keyboardTop=u+5;float pitch=(w-16)/16;for(int i=0;i<16;i++)add("",8+i*pitch,u+8+kh*.48f,pitch-2,kh*.52f-4,0,WHITES[i]+14);
        for(int[] b:BLACKS)add("",8+pitch*.5f+b[1]*pitch,u+10,pitch-2,kh*.43f,0,b[0]+14);
        if(artwork!=null)artwork.recycle();artwork=Bitmap.createBitmap(getWidth(),getHeight(),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(artwork);c.scale(scale,scale);drawBase(c,w,h);invalidate();}
    private int color(int value){return 0xff000000|value;}
    private void box(Canvas c,RectF r,float radius,int top,int bottom,int stroke){paint.setShader(new LinearGradient(0,r.top,0,r.bottom,color(top),color(bottom),Shader.TileMode.CLAMP));paint.setStyle(Paint.Style.FILL);c.drawRoundRect(r,radius,radius,paint);paint.setShader(null);if(stroke!=0){paint.setColor(color(stroke));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1f);c.drawRoundRect(r,radius,radius,paint);paint.setStyle(Paint.Style.FILL);}}
    private void text(Canvas c,String s,float x,float y,float size,int rgb){paint.setShader(null);paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(size);paint.setColor(color(rgb));String[] lines=s.split("\n");for(int i=0;i<lines.length;i++)c.drawText(lines[i],x,y+(i-(lines.length-1)/2f)*size*1.12f,paint);}
    private RectF lamp(Control a){return a.id>=14?new RectF(a.r.centerX()-2,a.r.top+10,a.r.centerX()+2,a.r.top+10+Math.max(16,a.r.height()*.4f)):new RectF(a.r.centerX()-7,a.r.top+3,a.r.centerX()+7,a.r.top+5);}
    private void drawBase(Canvas c,float w,float h){int[] p=PALETTES[finish];box(c,new RectF(.5f,.5f,w-.5f,h-.5f),17,p[0],p[1],p[2]);box(c,new RectF(3,keyboardTop,w-3,h-2),12,p[3],p[4],0x262a2b);
        for(Control a:controls){if(a.type==3){RectF b=new RectF(a.r);b.inset(-4,-4);box(c,b,8,0x161b1e,0x090c0f,0x677374);continue;}
            if(a.type==1||a.type==2){box(c,a.r,a.r.width()/2,0x171919,0x0e1010,0x575b5a);RectF b=new RectF(a.r);b.inset(3,3);box(c,b,b.width()/2,0x303332,0x171a19,0x494d4b);b.inset(6,6);box(c,b,b.width()/2,0x292c2b,0x1b1e1d,0);text(c,a.label,a.r.centerX(),a.r.top-5,10,p[5]);}
            else {box(c,a.r,a.id>=14?9:7,p[3],p[4],p[4]);if(a.id>=14){int outline=0;for(int shift:new int[]{16,8,0})outline|=Math.round(((p[4]>>shift)&255)*.74f+((p[6]>>shift)&255)*.26f)<<shift;RectF edge=new RectF(a.r);edge.inset(.65f,.65f);paint.setColor(color(outline));paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.3f);c.drawRoundRect(edge,8.35f,8.35f,paint);paint.setStyle(Paint.Style.FILL);}if(a.id<14)text(c,a.label,a.r.centerX(),a.r.centerY()+4,a.id<2?12:11,p[6]);else {for(int j=0;j<10;j++)if(BLACKS[j][0]==a.id-14)text(c,new String[]{"OP1","OP2","OP3","OP4","OP5","OP6","PIT","GLO","MONO","POLY"}[j],a.r.centerX(),a.r.bottom-5,9,p[6]);}paint.setColor(color(p[8]));RectF l=lamp(a);l.inset(finish==0?-2:-.75f,finish==0?-2:-.75f);c.drawRoundRect(l,3,3,paint);}}
    }
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(artwork==null)return;canvas.drawBitmap(artwork,0,0,null);canvas.save();canvas.scale(scale,scale);int[] p=PALETTES[finish];for(Control a:controls){if(a.type==3){if(screenReady){paint.setFilterBitmap(false);canvas.drawBitmap(screen,null,a.r,paint);}else{text(canvas,"FM–1",a.r.centerX(),a.r.centerY(),28,p[7]);}continue;}if(a.type==1||a.type==2){float angle=a.type==2?-130+master/1023f*260:-30+angles[a.id];double rad=Math.toRadians(angle-90);float r=a.r.width()*.32f;paint.setColor(0xffe2e6e3);paint.setStrokeWidth(2.7f);paint.setStrokeCap(Paint.Cap.ROUND);canvas.drawLine(a.r.centerX()+(float)Math.cos(rad)*r*.6f,a.r.centerY()+(float)Math.sin(rad)*r*.6f,a.r.centerX()+(float)Math.cos(rad)*r,a.r.centerY()+(float)Math.sin(rad)*r,paint);continue;}
            if(held[a.id]>0){paint.setColor(0x44000000);canvas.drawRoundRect(a.r,8,8,paint);}float duty=a.id<leds.length?leds[a.id]:0;float level=Math.max(0,Math.min(1,duty*1100/95));float alpha=level*1.03f/(level+.03f);if(alpha>0){RectF l=lamp(a);paint.setColor(color(p[7]));paint.setAlpha((int)(alpha*255));paint.setShadowLayer(finish==0?7:4.5f,0,0,color(p[7]));canvas.drawRoundRect(l,2,2,paint);paint.clearShadowLayer();paint.setAlpha(255);}}
        canvas.restore();}
    private int hit(float x,float y){for(int i=controls.size()-1;i>=0;i--)if(controls.get(i).r.contains(x,y))return i;if(y>=keyboardTop){float best=Float.MAX_VALUE;int result=-1;for(int i=0;i<controls.size();i++){Control c=controls.get(i);if(c.type==0&&c.id>=14){float dx=Math.max(c.r.left-x,Math.max(0,x-c.r.right)),dy=Math.max(c.r.top-y,Math.max(0,y-c.r.bottom)),d=dx*dx+dy*dy;if(d<best){best=d;result=i;}}}return result;}return -1;}
    private void hold(int index,boolean down){Control c=controls.get(index);if(c.type==0){if(down){if(held[c.id]++==0&&actions!=null)actions.key(c.id,true);}else if(held[c.id]>0&&--held[c.id]==0&&actions!=null)actions.key(c.id,false);}}
    public void releaseTouches(){for(int i=0;i<41;i++){if(held[i]>0&&actions!=null)actions.key(i,false);held[i]=0;}touches.clear();invalidate();}
    @Override public boolean onTouchEvent(MotionEvent e){int action=e.getActionMasked(),index=e.getActionIndex();if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){int c=hit(e.getX(index)/scale,e.getY(index)/scale);if(c>=0){touches.put(e.getPointerId(index),new Touch(c,e.getY(index)/scale));hold(c,true);}}else if(action==MotionEvent.ACTION_MOVE){for(int i=0;i<e.getPointerCount();i++){Touch t=touches.get(e.getPointerId(i));if(t==null)continue;Control c=controls.get(t.control);float y=e.getY(i)/scale;if(c.type==1||c.type==2){int steps=(int)((t.y-y)/4);if(steps!=0){t.y-=steps*4;if(c.type==2){master=Math.max(0,Math.min(1023,master+steps*12));if(actions!=null)actions.master(master);}else{angles[c.id]+=steps*5.7f;if(actions!=null)actions.encoder(c.id,steps);}}}else if(c.type==0&&c.id>=14){int next=hit(e.getX(i)/scale,y);if(next>=0&&controls.get(next).type==0&&controls.get(next).id>=14&&next!=t.control){hold(t.control,false);t.control=next;hold(next,true);}}}}else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP){Touch t=touches.get(e.getPointerId(index));if(t!=null){hold(t.control,false);if(controls.get(t.control).type==3&&actions!=null)actions.enlarge();touches.remove(e.getPointerId(index));}performClick();}else if(action==MotionEvent.ACTION_CANCEL)releaseTouches();invalidate();return true;}
    @Override public boolean performClick(){super.performClick();return true;}
    @Override protected void onDetachedFromWindow(){releaseTouches();super.onDetachedFromWindow();}
}
