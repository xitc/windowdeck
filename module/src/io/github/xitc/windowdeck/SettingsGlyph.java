package io.github.xitc.windowdeck;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** 自绘图标与箭头。形状按 24×24 坐标系描述，绘制时缩放到实际尺寸。 */
final class SettingsGlyph {
 static final int WINDOW=0,SIZE=1,NEW=2,SIDE=3,PLUS_MODE=4,APP=5,VIDEO=6,RESUME=7;
 private SettingsGlyph(){}
 static View icon(Context c,int glyph,int color){return new Icon(c,glyph,color);}
 static View chevron(Context c){return new Chevron(c);}
 private static final class Icon extends View {
  private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint stroke=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path=new Path();
  private final RectF rect=new RectF();
  private final int glyph;
  Icon(Context c,int glyph,int color){
   super(c);this.glyph=glyph;
   fill.setColor(color);fill.setStyle(Paint.Style.FILL);
   stroke.setColor(0xffffffff);stroke.setStyle(Paint.Style.STROKE);
   stroke.setStrokeWidth(1.7f);stroke.setStrokeCap(Paint.Cap.ROUND);stroke.setStrokeJoin(Paint.Join.ROUND);
   setForceDarkAllowed(false);
  }
  @Override protected void onMeasure(int widthSpec,int heightSpec){
   int size=Ui.dp(getContext(),SettingsTheme.ICON);setMeasuredDimension(size,size);
  }
  @Override protected void onDraw(Canvas canvas){
   int size=getWidth();if(size<=0)return;
   float r=size*0.3f;
   rect.set(0,0,size,size);canvas.drawRoundRect(rect,r,r,fill);
   float scale=size/24f*0.62f;
   canvas.save();
   canvas.translate(size/2f-12f*scale,size/2f-12f*scale);
   canvas.scale(scale,scale);
   stroke.setStrokeWidth(1.7f/0.62f);
   switch(glyph){
    case WINDOW:
     rect.set(3.5f,7,15,17.5f);canvas.drawRoundRect(rect,2.4f,2.4f,stroke);
     rect.set(9,3.5f,20.5f,14);canvas.drawRoundRect(rect,2.4f,2.4f,stroke);
     break;
    case SIZE:
     canvas.drawLine(4,12,20,12,stroke);
     canvas.drawCircle(14.5f,12,3.2f,fill);
     canvas.drawCircle(14.5f,12,3.2f,stroke);
     break;
    case NEW:
     rect.set(3.5f,3.5f,20.5f,20.5f);canvas.drawRoundRect(rect,5,5,stroke);
     canvas.drawLine(12,8,12,16,stroke);canvas.drawLine(8,12,16,12,stroke);
     break;
    case SIDE:
     path.reset();path.moveTo(5,4.5f);path.lineTo(19,7.5f);path.lineTo(19,16.5f);path.lineTo(5,19.5f);path.close();
     canvas.drawPath(path,stroke);
     break;
    case PLUS_MODE:
     rect.set(3,5,13,19);canvas.drawRoundRect(rect,2.6f,2.6f,stroke);
     canvas.drawLine(17.5f,8,17.5f,16,stroke);canvas.drawLine(13.5f,12,21.5f,12,stroke);
     break;
    case APP:
     rect.set(5,5,19,19);canvas.drawRoundRect(rect,4,4,stroke);
     canvas.drawCircle(12,12,2.2f,fill);
     break;
    case VIDEO:
     rect.set(3.5f,5.5f,20.5f,18.5f);canvas.drawRoundRect(rect,3,3,stroke);
     path.reset();path.moveTo(10.5f,9.5f);path.lineTo(10.5f,14.5f);path.lineTo(15,12);path.close();
     canvas.drawPath(path,fill);
     break;
    case RESUME:
     rect.set(5,5,19,19);
     canvas.drawArc(rect,-60,300,false,stroke);
     path.reset();path.moveTo(17.5f,3.5f);path.lineTo(17.5f,8.5f);path.lineTo(12.5f,8.5f);path.close();
     canvas.drawPath(path,fill);
     break;
    default:break;
   }
   canvas.restore();
  }
 }
 private static final class Chevron extends View {
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path=new Path();
  Chevron(Context c){
   super(c);
   paint.setColor(SettingsTheme.chevron(c));paint.setStyle(Paint.Style.STROKE);
   paint.setStrokeWidth(2f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
   setForceDarkAllowed(false);
  }
  @Override protected void onMeasure(int widthSpec,int heightSpec){
   int size=Ui.dp(getContext(),20);setMeasuredDimension(size,size);
  }
  @Override protected void onDraw(Canvas canvas){
   int w=getWidth();if(w<=0)return;
   float s=w/24f;canvas.scale(s,s);
   path.reset();path.moveTo(9,5);path.lineTo(15,12);path.lineTo(9,19);
   canvas.drawPath(path,paint);
  }
 }
}
