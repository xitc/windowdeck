package dev.windowdeck.app;
import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
final class Ui {
 static int dp(Context c,float n){return (int)(n*c.getResources().getDisplayMetrics().density+.5f);}
 static GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
 static TextView text(Context c,String s,int size,int color){TextView v=new TextView(c);v.setText(s);v.setTextSize(size);v.setTextColor(color);return v;}
 static Button button(Context c,String s){Button b=new Button(c);b.setText(s);b.setAllCaps(false);b.setTextSize(14);b.setMinWidth(0);b.setMinimumWidth(0);return b;}
 static void insets(View v){v.setOnApplyWindowInsetsListener((view,i)->{android.graphics.Insets b=i.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());view.setPadding(b.left,b.top,b.right,b.bottom);return i;});}
 static void round(View v,int radius){
  v.setClipToOutline(true);
  v.setOutlineProvider(new ViewOutlineProvider(){
   public void getOutline(View view,Outline outline){outline.setRoundRect(0,0,Math.max(0,view.getWidth()),Math.max(0,view.getHeight()),radius);}
  });
  v.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob)->view.invalidateOutline());
 }
 static View handle(Context c){
  View v=new View(c);
  v.setBackground(bg(0x66ffffff,dp(c,2)));
  return v;
 }
}
