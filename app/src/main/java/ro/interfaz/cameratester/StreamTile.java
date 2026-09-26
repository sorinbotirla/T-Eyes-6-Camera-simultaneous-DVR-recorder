package ro.interfaz.cameratester;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;

@SuppressLint({"SetTextI18n", "ViewConstructor"}) // Instantiated programmatically with a source, never inflated from XML.
final class StreamTile extends LinearLayout implements TextureView.SurfaceTextureListener {
    interface Listener {void ready();void frame(StreamTile tile);void lost();}
    final Source source;
    final TextureView texture;
    private final TextView status;
    private Listener listener;
    private int frames,width=640,height=480;
    private long start=SystemClock.elapsedRealtime(),lastFrame;
    private boolean first;
    private final java.util.List<StreamTile> mirrors=new java.util.ArrayList<>();
    private Bitmap mirrorBitmap;
    private long lastMirror;
    private boolean shared;
    StreamTile(Context c,String title,Source source,Runnable click){
        super(c);this.source=source;setOrientation(VERTICAL);setPadding(8,8,8,8);
        GradientDrawable bg=new GradientDrawable();bg.setColor(Color.rgb(20,31,40));bg.setCornerRadius(12);setBackground(bg);
        TextView name=new TextView(c);name.setText(title);name.setTextSize(17);name.setTextColor(Color.WHITE);name.setPadding(4,2,4,4);addView(name);
        texture=new TextureView(c);texture.setOpaque(true);texture.setSurfaceTextureListener(this);addView(texture,new LayoutParams(-1,0,1));
        status=new TextView(c);status.setTextSize(12);status.setTextColor(Color.rgb(137,183,197));status.setMaxLines(3);
        status.setText(source==null?"No source assigned · tap to choose":source.label+" · connecting…");addView(status);
        if(click!=null){setOnClickListener(v->click.run());texture.setOnClickListener(v->click.run());}
    }
    void listen(Listener listener){this.listener=listener;if(texture.isAvailable())listener.ready();}
    boolean ready(){return texture.isAvailable();}
    void dimensions(int w,int h){width=w;height=h;transform();}
    int frameWidth(){return width;}int frameHeight(){return height;}
    long lastFrame(){return lastFrame;}
    void message(String text){status.setText(text);}
    void mirrorTo(StreamTile other){other.shared=true;mirrors.add(other);}
    void clearMirrors(){mirrors.clear();if(mirrorBitmap!=null){mirrorBitmap.recycle();mirrorBitmap=null;}}
    void draw(Bitmap bitmap){
        if(!texture.isAvailable())return;
        dimensions(bitmap.getWidth(),bitmap.getHeight());
        Canvas canvas=texture.lockCanvas();if(canvas==null)return;
        try{canvas.drawColor(Color.BLACK);canvas.drawBitmap(bitmap,null,new Rect(0,0,canvas.getWidth(),canvas.getHeight()),null);}
        finally{texture.unlockCanvasAndPost(canvas);}
    }
    private void transform(){if(texture.getWidth()==0||texture.getHeight()==0)return;
        float w=texture.getWidth(),h=texture.getHeight(),scale=Math.min(w/width,h/height);Matrix m=new Matrix();m.setScale(width*scale/w,height*scale/h,w/2,h/2);texture.setTransform(m);}
    @Override public void onSurfaceTextureAvailable(SurfaceTexture st,int w,int h){transform();if(listener!=null)listener.ready();}
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st,int w,int h){transform();}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st){if(listener!=null)listener.lost();return true;}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st){
        lastFrame=SystemClock.elapsedRealtime();frames++;
        if(!first){first=true;if(listener!=null)listener.frame(this);}
        if(!mirrors.isEmpty()&&lastFrame-lastMirror>=100){
            lastMirror=lastFrame;
            // One TEYES producer per channel; aliases reuse its image locally at up to 10 FPS.
            if(mirrorBitmap==null)mirrorBitmap=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888);
            Bitmap copy=texture.getBitmap(mirrorBitmap);
            if(copy!=null)for(StreamTile mirror:mirrors)mirror.draw(copy);
        }
        if(lastFrame-start>=1000){
            String prefix=shared?"Shared channel · ":source!=null&&source.kind.equals("teyes")?"TEYES frames · ":width+" × "+height+" · ";
            status.setText(String.format(java.util.Locale.ROOT,"%s%.1f FPS",prefix,frames*1000.0/(lastFrame-start)));frames=0;start=lastFrame;
        }
    }
}
