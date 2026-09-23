package dev.windowdeck.app;
public final class SurfaceFitTest {
 public static void main(String[] args){
  int[] phone=SurfaceFit.coverCrop(1440,3168,74,106);
  check(phone[0]==0&&phone[2]==1440,"cover uses full source width");
  check(phone[3]>=2000&&phone[3]<3168,"cover crops source height");
  check(phone[1]==(3168-phone[3])/2,"cover is vertically centered");
  float scale=SurfaceFit.coverScale(phone,74,106);
  check(Math.abs(phone[2]*scale-74)<=1,"scaled crop fills plate width");
  check(Math.abs(phone[3]*scale-106)<=1,"scaled crop fills plate height");
  int[] letter=SurfaceFit.coverCrop(1440,3168,280,616);
  check(letter[2]==1440&&letter[3]==3168,"matching aspect keeps full buffer");
  int[] wide=SurfaceFit.coverCrop(3168,1440,74,106);
  check(wide[1]==0&&wide[3]==1440,"landscape cover uses full source height");
  check(wide[2]<3168,"landscape cover crops source width");
  System.out.println("PASS cover-crop source windows");
 }
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
