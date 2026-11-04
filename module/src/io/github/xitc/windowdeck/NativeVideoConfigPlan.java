package io.github.xitc.windowdeck;

import java.util.*;
import java.util.regex.*;

/** Byte-format-preserving edits of WindowDeck-owned native video policy blocks. */
final class NativeVideoConfigPlan {
 static final String BEGIN="<!-- WindowDeck native video compatibility BEGIN -->";
 static final String END="<!-- WindowDeck native video compatibility END -->";
 static final String BILI_BEGIN="<!-- WindowDeck Bili unresizable BEGIN -->";
 static final String BILI_END="<!-- WindowDeck Bili unresizable END -->";
 private static final Pattern VERSION=Pattern.compile("(<version>\\s*)([0-9]+)(\\s*</version>)");
 private static final Pattern ENTRY=Pattern.compile("<psmwbp\\b[^>]*\\battr\\s*=\\s*([\"'])([^\"']+)\\1[^>]*/?>");
 final String xml;
 final long version;
 final Set<String> managed;
 final boolean changed;
 private NativeVideoConfigPlan(String xml,long version,Set<String> managed,boolean changed){this.xml=xml;this.version=version;this.managed=managed;this.changed=changed;}
 static boolean validPackage(String pkg){
  return pkg!=null&&pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
   &&!Arrays.asList("android","dev.windowdeck.app","io.github.xitc.windowdeck","com.oplus.pscanvas","com.android.launcher","com.android.systemui").contains(pkg);
 }
 private static String uncomment(String xml){return xml.replaceAll("(?s)<!--.*?-->","");}
 private static String maskComments(String xml){
  char[] text=xml.toCharArray();Matcher comments=Pattern.compile("(?s)<!--.*?-->").matcher(xml);
  while(comments.find())Arrays.fill(text,comments.start(),comments.end(),' ');return new String(text);
 }
 static long version(String xml){
  Matcher m=VERSION.matcher(uncomment(xml));if(!m.find())throw new IllegalArgumentException("配置缺少版本");
  long value=Long.parseLong(m.group(2));if(m.find())throw new IllegalArgumentException("配置版本重复");return value;
 }
 static Set<String> entries(String xml){
  TreeSet<String> entries=new TreeSet<>();Matcher m=ENTRY.matcher(uncomment(xml));while(m.find())entries.add(m.group(2));return entries;
 }
 private static int[] block(String xml){
  int begin=xml.indexOf(BEGIN),end=xml.indexOf(END);
  if(begin<0&&end<0)return null;
  if(begin<0||end<begin||xml.indexOf(BEGIN,begin+BEGIN.length())>=0||xml.indexOf(END,end+END.length())>=0)throw new IllegalArgumentException("兼容配置标记损坏，未修改系统文件");
  String content=xml.substring(begin+BEGIN.length(),end);
  String remainder=ENTRY.matcher(uncomment(content)).replaceAll("").trim();
  if(!remainder.isEmpty())throw new IllegalArgumentException("兼容区域存在其他配置，未修改系统文件");
  for(String pkg:entries(content))if(!validPackage(pkg))throw new IllegalArgumentException("兼容区域包名无效");
  int start=begin,line=xml.lastIndexOf('\n',begin)+1;
  if(xml.substring(line,begin).trim().isEmpty())start=line;
  int finish=end+END.length();
  if(xml.startsWith("\r\n",finish))finish+=2;else if(xml.startsWith("\n",finish))finish++;
  return new int[]{start,finish,begin+BEGIN.length(),end};
 }
 static Set<String> managed(String xml){int[] b=block(xml);return b==null?new TreeSet<>():entries(xml.substring(b[2],b[3]));}
 static NativeVideoConfigPlan edit(String original,Collection<String> requested,long loaded){
  long old=version(original);if(old!=loaded)throw new IllegalArgumentException("系统配置尚未同步，请重新读取后再应用");
  TreeSet<String> wanted=new TreeSet<>();for(String pkg:requested){if(!validPackage(pkg))throw new IllegalArgumentException("不支持的应用包名");wanted.add(pkg);}
  int[] b=block(original);String base=b==null?original:original.substring(0,b[0])+original.substring(b[1]);
  wanted.removeAll(entries(base)); // Never own or delete factory/vendor entries.
  if(wanted.equals(managed(original)))return new NativeVideoConfigPlan(original,old,wanted,false);
  String result=base;
  if(!wanted.isEmpty()){
   int close=base.lastIndexOf("</settings>");if(close<0)throw new IllegalArgumentException("配置缺少根节点");
   String newline=base.contains("\r\n")?"\r\n":"\n";
   StringBuilder added=new StringBuilder("    ").append(BEGIN).append(newline);
   for(String pkg:wanted)added.append("    <psmwbp attr=\"").append(pkg).append("\"/>").append(newline);
   added.append("    ").append(END).append(newline);result=base.substring(0,close)+added+base.substring(close);
  }
  if(old==Long.MAX_VALUE)throw new IllegalArgumentException("配置版本超出范围");
  Matcher v=VERSION.matcher(maskComments(result));if(!v.find())throw new IllegalArgumentException("配置版本损坏");
  result=result.substring(0,v.start(2))+(old+1)+result.substring(v.end(2));
  return new NativeVideoConfigPlan(result,old+1,wanted,true);
 }
 static NativeVideoConfigPlan editBiliUnresizable(String original,boolean enabled,long loaded){
  long old=version(original);if(old!=loaded)throw new IllegalArgumentException("系统配置尚未同步，请重新读取后再应用");
  int[] owned=ownedBiliBlock(original);String base=owned==null?original:original.substring(0,owned[0])+original.substring(owned[1]);
  int sectionStart=base.indexOf("<forceAppToUnResizable>");int sectionEnd=base.indexOf("</forceAppToUnResizable>");
  if(sectionStart<0||sectionEnd<sectionStart||base.indexOf("<forceAppToUnResizable>",sectionStart+1)>=0)throw new IllegalArgumentException("配置中的 forceAppToUnResizable 区域异常");
  boolean factoryHasBili=Pattern.compile("<package\\b(?=[^>]*\\bname\\s*=\\s*([\"'])tv\\.danmaku\\.bili\\1)[^>]*/?>").matcher(uncomment(base.substring(sectionStart,sectionEnd))).find();
  if(enabled&&factoryHasBili)throw new IllegalArgumentException("系统已有 B 站 forceAppToUnResizable 条目，无需重复添加");
  if(enabled==(owned!=null))return new NativeVideoConfigPlan(original,old,enabled?Collections.singleton("tv.danmaku.bili"):Collections.emptySet(),false);
  String result=base;
  if(enabled){
   int close=base.lastIndexOf("</forceAppToUnResizable>");if(close<0)throw new IllegalArgumentException("配置缺少 forceAppToUnResizable 区域");
   String newline=base.contains("\r\n")?"\r\n":"\n";
   String block="    "+BILI_BEGIN+newline+"        <package name=\"tv.danmaku.bili\" type=\"1:0:0\"/>"+newline+"    "+BILI_END+newline;
   int line=base.lastIndexOf('\n',close)+1;result=base.substring(0,line)+block+base.substring(line);
  }
  if(old==Long.MAX_VALUE)throw new IllegalArgumentException("配置版本超出范围");
  Matcher v=VERSION.matcher(maskComments(result));if(!v.find())throw new IllegalArgumentException("配置版本损坏");
  result=result.substring(0,v.start(2))+(old+1)+result.substring(v.end(2));
  return new NativeVideoConfigPlan(result,old+1,enabled?Collections.singleton("tv.danmaku.bili"):Collections.emptySet(),true);
 }
 private static int[] ownedBiliBlock(String xml){
  int begin=xml.indexOf(BILI_BEGIN),end=xml.indexOf(BILI_END);if(begin<0&&end<0)return null;
  if(begin<0||end<begin||xml.indexOf(BILI_BEGIN,begin+BILI_BEGIN.length())>=0||xml.indexOf(BILI_END,end+BILI_END.length())>=0)throw new IllegalArgumentException("B 站试验配置标记损坏，未修改系统文件");
  String content=xml.substring(begin+BILI_BEGIN.length(),end).trim();if(!content.matches("<package\\s+name=\"tv\\.danmaku\\.bili\"\\s+type=\"1:0:0\"\\s*/>"))throw new IllegalArgumentException("B 站试验区域内容异常，未修改系统文件");
  int start=begin,line=xml.lastIndexOf('\n',begin)+1;if(xml.substring(line,begin).trim().isEmpty())start=line;
  int finish=end+BILI_END.length();if(xml.startsWith("\r\n",finish))finish+=2;else if(xml.startsWith("\n",finish))finish++;return new int[]{start,finish};
 }
}
