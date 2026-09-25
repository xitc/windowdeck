package dev.windowdeck.app;

public final class BackdropPolicyTest {
 public static void main(String[] args){
  check(BackdropPolicy.rotationDegrees(0)==0,"natural rotation");
  check(BackdropPolicy.rotationDegrees(1)==-90,"rotation 90 matches vivo");
  check(BackdropPolicy.rotationDegrees(2)==180,"rotation 180");
  check(BackdropPolicy.rotationDegrees(3)==90,"rotation 270 matches vivo");
  check(BackdropPolicy.shouldRotate(1080,2400,2400,1080),"portrait bitmap on landscape display");
  check(!BackdropPolicy.shouldRotate(1080,2400,1080,2400),"portrait bitmap on portrait display");
  check(!BackdropPolicy.shouldRotate(2400,1080,2400,1080),"landscape bitmap on landscape display");
  check(BackdropPolicy.shouldRotate(2400,1080,1080,2400),"landscape bitmap on portrait display");
  check(!BackdropPolicy.shouldRotate(0,0,1080,2400),"empty bitmap skips rotate");
  check(BackdropPolicy.needsBitmapRotation(false,1,1080,2400,2400,1080),"raw wallpaper follows display rotation");
  check(BackdropPolicy.needsBitmapRotation(false,2,1080,2400,1080,2400),"raw wallpaper still rotates 180");
  check(!BackdropPolicy.needsBitmapRotation(true,1,2400,1080,2400,1080),"system blur already matches display");
  check(BackdropPolicy.needsBitmapRotation(true,1,1080,2400,2400,1080),"system blur rotates if aspect mismatches");
  check(!BackdropPolicy.needsBitmapRotation(true,2,1080,2400,1080,2400),"preoriented 180 keeps matching aspect");
  check(!BackdropPolicy.needsBitmapRotation(false,0,1080,2400,1080,2400),"natural display skips rotate");
  check(BackdropPolicy.lightBars(1),"dark icons on light wallpaper");
  check(!BackdropPolicy.lightBars(0),"light icons on dark wallpaper");
  check(BackdropPolicy.DIM==0x1e000000,"vivo dim overlay");
  System.out.println("PASS backdrop rotation and luminance policy");
 }
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
