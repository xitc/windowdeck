package io.github.xitc.windowdeck;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** C17 设置卡片里的一行：40 dp 图标 + 标题／说明 + 右侧箭头、当前值或开关。 */
final class SettingsRow {
 interface Toggle{boolean apply(boolean checked);}
 final LinearLayout view;
 final TextView title,summary,value;
 final CouiSwitch toggle;
 private SettingsRow(LinearLayout view,TextView title,TextView summary,TextView value,CouiSwitch toggle){
  this.view=view;this.title=title;this.summary=summary;this.value=value;this.toggle=toggle;
 }
 static SettingsRow action(Context c,int glyph,int color,String titleText,String summaryText,String valueText){
  LinearLayout row=base(c,glyph,color);
  LinearLayout text=textColumn(c,row,glyph>=0);
  TextView title=SettingsTheme.title(c,titleText);text.addView(title);
  TextView summary=null;
  if(summaryText!=null&&!summaryText.isEmpty()){summary=SettingsTheme.summary(c,summaryText);text.addView(summary);}
  TextView value=null;
  if(valueText!=null){
   value=SettingsTheme.summary(c,valueText);value.setGravity(Gravity.END);
   LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.leftMargin=Ui.dp(c,8);
   row.addView(value,lp);
  }
  row.addView(SettingsGlyph.chevron(c),chevronParams(c));
  return new SettingsRow(row,title,summary,value,null);
 }
 static SettingsRow switchRow(Context c,int glyph,int color,String titleText,String summaryText,boolean checked,Toggle handler){
  LinearLayout row=base(c,glyph,color);
  LinearLayout text=textColumn(c,row,glyph>=0);
  TextView title=SettingsTheme.title(c,titleText);text.addView(title);
  TextView summary=null;
  if(summaryText!=null&&!summaryText.isEmpty()){summary=SettingsTheme.summary(c,summaryText);text.addView(summary);}
  CouiSwitch toggle=new CouiSwitch(c);toggle.setChecked(checked,false);
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.leftMargin=Ui.dp(c,16);
  row.addView(toggle,lp);
  row.setOnClickListener(v->{
   boolean wanted=!toggle.isChecked();
   if(handler==null||handler.apply(wanted))toggle.setChecked(wanted,true);
  });
  return new SettingsRow(row,title,summary,null,toggle);
 }
 void setValue(String text){if(value!=null)value.setText(text);}
 void setEnabled(boolean enabled){view.setEnabled(enabled);view.setAlpha(enabled?1f:0.4f);}
 private static LinearLayout base(Context c,int glyph,int color){
  LinearLayout row=new LinearLayout(c);
  row.setOrientation(LinearLayout.HORIZONTAL);
  row.setGravity(Gravity.CENTER_VERTICAL);
  row.setMinimumHeight(Ui.dp(c,glyph>=0?SettingsTheme.ROW_ICON_MIN:SettingsTheme.ROW_MIN));
  int pad=Ui.dp(c,SettingsTheme.ROW_PADDING);
  row.setPadding(pad,0,pad,0);
  row.setBackground(SettingsTheme.ripple(c));
  row.setClickable(true);row.setFocusable(true);
  if(glyph>=0)row.addView(SettingsGlyph.icon(c,glyph,color));
  return row;
 }
 private static LinearLayout textColumn(Context c,LinearLayout row,boolean hasIcon){
  LinearLayout text=new LinearLayout(c);
  text.setOrientation(LinearLayout.VERTICAL);
  text.setPadding(hasIcon?Ui.dp(c,16):0,Ui.dp(c,10),Ui.dp(c,8),Ui.dp(c,10));
  row.addView(text,new LinearLayout.LayoutParams(0,-2,1f));
  return text;
 }
 private static LinearLayout.LayoutParams chevronParams(Context c){
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.leftMargin=Ui.dp(c,4);return lp;
 }
}
