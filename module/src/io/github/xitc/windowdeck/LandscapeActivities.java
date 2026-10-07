package io.github.xitc.windowdeck;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** OriginOS FULLSCRREN_COMPANT_FOR_LANDSCAPE_ASPECTRARIO, not a package whitelist.
 * Source and callers: evidence/v050dev143/vivo-landscape-list.txt.
 * This is aspect-ratio classification, not playback or sensor detection.
 */
final class LandscapeActivities {
 private static final String YOUKU_DETAIL="com.youku.ui.activity.DetailActivity";
 private static final Set<String> ORIGINAL=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
  "org.qiyi.pluginlibrary.component.InstrActivityProxy1",
  "com.qiyi.qxsv.shortplayer.shortplayer.InternalShortPlayerActivity",
  "com.tencent.qqlive.ona.activity.VideoDetailActivity",
  "com.tencent.qqlive.universal.wtoe.immersive.page.WTOEImmersivePlayActivity",
  "com.tencent.qqlive.ona.activity.SplashVideoDetailActivity",
  "com.tencent.qqlive.universal.live.ui.UniversalLiveActivity",
  "com.tencent.qqlive.ona.activity.LocalVideoPlayerActivity",
  "com.tencent.qqlive.kmm.VideoDetailKmmActivityBk",
  "com.tencent.vlive.VLiveActivity",
  "com.youku.hotspot.activity.HotSpotActivity",
  "com.vivo.remotecontrol.ui.remotecontrol.control.RemoteControlActivity",
  "com.biliintl.playdetail.page.host.PlayDetailActivity",
  "com.tencent.gcloud.msdk.core.policy.MSDKPolicyActivity",
  "com.sohu.sohuvideo.mvp.ui.activity.VideoDetailActivity",
  "org.isuike.video.activity.PlayerActivity",
  "com.elinkway.infinitemovies.play.core.VideoDetailActivity",
  "com.vivo.video.longvideo.ui.LongVideoDetailActivity",
  "com.vivo.video.online.shortvideo.detail.activity.ShortVideoDetailActivity",
  "com.vivo.video.uploader.uploaderdetail.view.SingleUploaderImmersiveActivity",
  "com.vivo.video.local.localplayer.LocalInnerPlayerActivity",
  "com.cmcc.cmvideo.playdetail.PlayDetailActivity",
  "com.cmcc.cmvideo.playdetail.widget.PlayLiveActivity",
  "com.cmvideo.capability.livependantlayout.activity.WorldCupPlayDetailActivity",
  "com.cmvideo.capability.mgdetail.StandardActivity",
  "com.cmvideo.capability.vod.VodActivity",
  "com.cmvideo.capability.mglivependant.palyerdetail.service.SingleTaskActivity",
  "com.cmvideo.capability.playdetailshortvideo.framework.SVideoDetailActivity",
  "com.cmcc.cmvideo.com.cmvideo.capability.mgdetail.SingleTaskActivity",
  "com.baidu.netdisk.video.ui.preview.video.VideoPlayerActivity",
  "com.baidu.netdisk.video.VideoPlayerActivity",
  "tv.acfun.core.module.videodetail.VideoDetailActivity",
  "com.storm.smart.activity.VideoPlayerActivity",
  "com.funshion.video.activity.MediaPlayActivity",
  "com.letv.android.client.album.AlbumPlayActivity",
  "com.letv.android.client.activity.MainActivity",
  "com.shinemo.miguaikan.biz.play.PlayDetailActivity",
  "com.shinemo.miguaikan.biz.main.live.TvLiveActivity",
  "com.tencent.qqsports.immersivev1.ImmersiveVideoListActivity",
  "com.tencent.qqsports.matchdetail.MatchDetailExActivity",
  "com.vivo.defaultPlayer.SystemVideoPlayActivity",
  "com.netflix.mediaclient.ui.player.PlayerActivity",
  "com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity",
  "com.google.android.apps.youtube.app.watchwhile.InternalMainActivity",
  "com.mgtv.ui.player.VodPlayerPageActivity",
  "com.mgtv.ui.videoplay.MGVideoPlayActivity",
  "com.tencent.tmgp.cf.AFMainActivity",
  "com.mxtech.videoplayer.ad.ActivityScreen",
  "com.mxtech.videoplayer.ad.online.gaana.OnlineGaanaPlayerActivity",
  "com.nitesh.vidmadevideo.OnvideoActivity",
  "com.rajesh.altt.OnvideoActivity",
  "com.google.android.apps.play.movies.mobile.usecase.watch.WatchActivity$InitiallyLandscape",
  "com.ss.android.ugc.aweme.feed.landscape.LandscapeFeedActivity",
  "com.vidio.android.watch.newplayer.WatchActivityAutoPiP",
  "free.tube.premium.advanced.tuber.main.MainActivity",
  "com.loklok.flash.android.module_detail.DetailActivity",
  "com.songs.bubble.player.main.MainActivity",
  "com.ss.android.ugc.aweme.longervideo.landscape.home.activity.LandscapeFeedActivity",
  "com.viu.phone.ui.activity.DemandActivity"
 )));
 private static String className(String component){
  if(component==null)return null;
  int slash=component.indexOf('/');
  if(slash<1||slash==component.length()-1)return null;
  String name=component.substring(slash+1);
  return name.startsWith(".")?component.substring(0,slash)+name:name;
 }
 static boolean original(String component,boolean fos15){
  String name=className(component);
  return ORIGINAL.contains(name)||(!fos15&&YOUKU_DETAIL.equals(name));
 }
 static String source(String component){
  if(original(component,false))return "vivo_activity_list"; // ColorOS is not FOS15.
  // MainActivity is shared by YouTube pages. Do not infer permanent landscape
  // from its class name; dynamic direction and user rotation lock are required.
  return null;
 }
 static String dynamicSource(String component){
  String original=source(component);
  if(original!=null)return original;
  return "com.google.android.youtube/com.google.android.apps.youtube.app.watchwhile.MainActivity".equals(component)?"youtube_dynamic_compat":null;
 }
 static int initialAxis(String component,int manifestAxis){return manifestAxis;}
 private LandscapeActivities(){}
}
