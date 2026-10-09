package io.github.xitc.windowdeck;

import java.util.*;
import java.util.regex.*;

/** Byte-format-preserving edit of only WindowDeck's owned psmwbp block. */
final class NativeVideoConfigPlan {
 static final String BEGIN="<!-- WindowDeck native video compatibility BEGIN -->";
 static final String END="<!-- WindowDeck native video compatibility END -->";
 private static final Pattern VERSION=Pattern.compile("(<version>\\s*)([0-9]+)(\\s*</version>)");
 private static final Pattern ENTRY=Pattern.compile("<psmwbp\\b[^>]*\\battr\\s*=\\s*([\"'])([^\"']+)\\1[^>]*/?>");
 final String xml;
 final long version;
 final Set<String> managed;
 final boolean changed;
 private NativeVideoConfigPlan(String xml,long version,Set<String> managed,boolean changed){this.xml=xml;this.version=version;this.managed=managed;this.changed=changed;}
 static boolean validPackage(String pkg){
  return pkg!=null&&pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
   &&!Arrays.asList("android","io.github.xitc.windowdeck","io.github.xitc.windowdeck","com.oplus.pscanvas","com.android.launcher","com.android.systemui").contains(pkg);
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
}
