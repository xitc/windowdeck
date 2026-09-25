package dev.windowdeck.app;
public final class SwipePanelPolicyTest {
 public static void main(String[] args){
  if(SwipePanelPolicy.selected(1f,1.15f,0,64)||SwipePanelPolicy.selected(1.5f,1.15f,65,64)||SwipePanelPolicy.selected(1.5f,1.15f,-65,64))throw new AssertionError("side/retreat selected");
  if(!SwipePanelPolicy.selected(1.5f,1.15f,0,64))throw new AssertionError("center missed");
  for(float d:new float[]{1,2.75f,4})for(int percent=0;percent<=100;percent++){
   float[] box=SwipePanelPolicy.box((int)(360*d),(int)(792*d),d,40*d,68*d,percent/100f);
   if(Math.abs(box[0]+box[2]/2-180*d)>0.01||box[0]<0||box[1]+box[3]>792*d||box[2]<112*d||box[3]<68*d)throw new AssertionError("expansion bounds");
  }
  if(SwipePanelPolicy.background(0)!=0x33ffffff||SwipePanelPolicy.background(1)!=0x99ffffff)throw new AssertionError("official background");
  if(SwipePanelPolicy.glyph(0)!=0xffffffff||SwipePanelPolicy.glyph(1)!=0xff000000)throw new AssertionError("official glyph");
  float[] upper=new float[]{16,176},lower=new float[]{184,344};
  SwipePanelPolicy.separateVertical(upper,lower,104);
  if(upper[0]!=16||upper[1]!=120||lower[0]!=240||lower[1]!=344)throw new AssertionError("landscape gap");
  float[] chip=SwipePanelPolicy.landscapeChip(684,752,upper[1],lower[0],112,0);
  if(Math.abs(chip[0]+chip[2]/2f-718)>0.01||Math.abs(chip[1]+chip[3]/2f-180)>0.01||chip[2]!=112||chip[3]!=68)throw new AssertionError("landscape chip");
  if(SwipePanelPolicy.chipRotation(1)!=90||SwipePanelPolicy.chipRotation(3)!=270||SwipePanelPolicy.landscapeStack(0))throw new AssertionError("landscape rotation");
  System.out.println("PASS 303 center expansion samples and side/retreat hit exclusion");
 }
}
