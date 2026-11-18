package com.iptvpro2.player;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.os.SystemClock;
import java.util.concurrent.*;
import java.security.MessageDigest;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

public class MainActivity extends Activity {
  private static final String SERVER="http://lgpro.top";
  private static final int BG=0xff060d1c, PANEL=0xff102039, BLUE=0xff1477f4;
  private LinearLayout root;
  private String username="",password="",currentType="",currentCategory="",currentSeries="";
  private boolean authenticated=false;
  private boolean loginPending=false;
  private long lastLoginAttempt=0;
  private final ThreadPoolExecutor filterExecutor=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new LinkedBlockingDeque<Runnable>(1),new ThreadPoolExecutor.DiscardOldestPolicy());
  private final ThreadPoolExecutor networkExecutor=new ThreadPoolExecutor(2,2,30L,TimeUnit.SECONDS,new LinkedBlockingDeque<Runnable>(8),new ThreadPoolExecutor.DiscardOldestPolicy());
  private int requestGeneration=0;
  private int playbackGeneration=0;
  private boolean resumeApplied=false;
  private int fallbackBudget=0;
  private int filterGeneration=0;
  private int fallbackAttempts=0;
  private boolean userRequestedPause=false;
  private ExoPlayer player;
  private int screenToken=0;
  private Runnable backAction;
  private String currentListTitle="";
  private JSONArray currentList;
  private String currentListKey;
  private Pick currentPick;
  private Runnable currentListBack;
  private String playerBackLabel="";
  private Runnable playerBack;
  private String activeStreamUrl="";
  private boolean playbackScreen=false, resumeOnForeground=false;
  private long lastPosition=0;
  private int listPage=0;
  private static final int PAGE_SIZE=80;
  private final Handler searchHandler=new Handler(Looper.getMainLooper());
  private Runnable pendingSearch;
  private String currentMediaKey="", currentMediaType="";
  private String currentStreamId="",currentStreamType="",currentStreamExt="";
  private TextView playbackStatus;
  private int alternateIndex=0;
  private String[] alternatives=new String[0];
  private String positionKey(){
    try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest((username+"@"+SERVER+"|"+currentMediaKey).getBytes("UTF-8"));
      StringBuilder hex=new StringBuilder();for(byte b:bytes)hex.append(String.format(Locale.ROOT,"%02x",b&255));return hex.toString();
    }catch(Exception e){return username+"|"+currentMediaKey;}
  }
  private SharedPreferences positions(){return getSharedPreferences("playback_positions",MODE_PRIVATE);}
  private void savePosition(){
    if(player!=null && !currentMediaKey.isEmpty() && !"live".equals(currentMediaType)){
      long pos=player.getCurrentPosition();if(player.getPlaybackState()==Player.STATE_ENDED)return;if(pos>5000)positions().edit().putLong(positionKey(),pos).apply();
    }
  }

  @Override public void onCreate(Bundle b){super.onCreate(b);login();}
  private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(size);t.setPadding(18,16,18,16);return t;}
  private void screen(String title){screenToken++;requestGeneration++;playbackScreen=false;root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(BG);root.setPadding(18,25,18,12);setContentView(root);TextView heading=text(title,25);heading.setTextColor(0xff5bb0ff);root.addView(heading);}
  private Button button(String s,Runnable action){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setBackgroundTintList(ColorStateList.valueOf(BLUE));b.setOnClickListener(v->action.run());return b;}
  private EditText input(String hint,boolean secret){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setHintTextColor(0xff8197b8);e.setTextColor(Color.WHITE);e.setBackgroundTintList(ColorStateList.valueOf(0xff5bb0ff));if(secret)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);root.addView(e);return e;}
  private void login(){loginPending=false;requestGeneration++;filterGeneration++;release();currentMediaKey="";activeStreamUrl="";resumeOnForeground=false;authenticated=false;username="";password="";screen("IPTV PRO2");backAction=null;root.addView(text("Votre divertissement, simplement.",16));EditText user=input("Code utilisateur",false),pass=input("Mot de passe",true);Button submit=button("SE CONNECTER",()->{String u=user.getText().toString().trim(),p=pass.getText().toString();if(u.isEmpty()||p.isEmpty()){toast("Saisissez vos identifiants");return;}submitLogin(u,p);});root.addView(submit);}
  private void submitLogin(String u,String p){
    if(loginPending||SystemClock.elapsedRealtime()-lastLoginAttempt<900)return;
    lastLoginAttempt=SystemClock.elapsedRealtime();loginPending=true;
    request("/player_api.php?username="+enc(u)+"&password="+enc(p),data->{loginPending=false;JSONObject auth=data.optJSONObject("user_info");boolean valid=auth!=null&&auth.optInt("auth",0)==1;String status=auth==null?"":auth.optString("status","");if(valid&&!"Disabled".equalsIgnoreCase(status)&&!"Banned".equalsIgnoreCase(status)&&!"Expired".equalsIgnoreCase(status)){username=u;password=p;authenticated=true;home();}else toast("Connexion refusée ou compte inactif");},()->{loginPending=false;});}
  private boolean guard(){if(!authenticated||username.isEmpty()||password.isEmpty()){login();return false;}return true;}
  private void home(){release();if(!guard())return;screen("IPTV PRO2");backAction=null;root.addView(button("TV EN DIRECT",()->categories("live")));root.addView(button("FILMS",()->categories("vod")));root.addView(button("SÉRIES",()->categories("series")));root.addView(button("DÉCONNEXION",this::login));}
  private String api(String action){return "/player_api.php?username="+enc(username)+"&password="+enc(password)+"&action="+action;}
  private void categories(String type){if(!guard())return;currentType=type;String action=type.equals("live")?"get_live_categories":type.equals("vod")?"get_vod_categories":"get_series_categories";request(api(action),data->showList("Catégories",array(data),"category_name",this::home,item->items(type,item.optString("category_id"))));}
  private void items(String type,String category){if(!guard())return;currentType=type;currentCategory=category;String action=type.equals("live")?"get_live_streams":type.equals("vod")?"get_vod_streams":"get_series";request(api(action)+"&category_id="+enc(category),data->showList("Catalogue",array(data),"name",()->categories(type),item->{if(type.equals("series")){currentSeries=item.optString("series_id");series(currentSeries);}else{String id=item.optString("stream_id");play(id,type,item.optString("container_extension",type.equals("live")?"m3u8":"mp4"),()->items(type,category));}}));}
  private void series(String id){if(!guard())return;request(api("get_series_info")+"&series_id="+enc(id),data->{JSONObject seasons=data.optJSONObject("episodes");JSONArray all=new JSONArray();if(seasons!=null){List<String> keys=new ArrayList<>();Iterator<String> it=seasons.keys();while(it.hasNext())keys.add(it.next());Collections.sort(keys,(a,b)->{try{return Integer.compare(Integer.parseInt(a),Integer.parseInt(b));}catch(Exception e){return a.compareTo(b);}});for(String key:keys){JSONArray eps=seasons.optJSONArray(key);if(eps!=null)for(int i=0;i<eps.length();i++){JSONObject ep=eps.optJSONObject(i);if(ep!=null)all.put(ep);}}}showList("Épisodes",all,"title",()->items("series",currentCategory),item->{JSONObject info=item.optJSONObject("info");String ext=item.optString("container_extension",info!=null?info.optString("container_extension","mp4"):"mp4");play(item.optString("id"),"series",ext,()->series(id));});});}
  private JSONArray array(JSONObject data){JSONArray a=data.optJSONArray("items");return a==null?new JSONArray():a;}
  interface Pick{void chosen(JSONObject item);}
  private void showList(String title,JSONArray data,String key,Runnable back,Pick pick){if(!guard())return;currentListTitle=title;currentList=data;currentListKey=key;currentListBack=back;currentPick=pick;listPage=0;renderList("");}
  private void renderList(String filter){if(!guard())return;filterGeneration++;screen(currentListTitle);backAction=currentListBack;root.addView(button("‹ RETOUR",currentListBack));EditText search=input("Rechercher...",false);ScrollView scroll=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(1);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));search.setText(filter);search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){if(pendingSearch!=null)searchHandler.removeCallbacks(pendingSearch);
        String q=s.toString();pendingSearch=()->{listPage=0;populate(list,q);};searchHandler.postDelayed(pendingSearch,220);}public void afterTextChanged(android.text.Editable e){}});populate(list,filter);}
  private void populate(LinearLayout list,String filter){
    final int generation=++filterGeneration;
    final JSONArray data=currentList;
    final String key=currentListKey;
    final String needle=filter.toLowerCase(Locale.ROOT);
    list.removeAllViews();list.addView(text("Recherche…",14));
    filterExecutor.getQueue().clear();filterExecutor.execute(()->{
      ArrayList<JSONObject> matches=new ArrayList<>();
      if(data!=null)for(int j=0;j<data.length();j++){
        JSONObject item=data.optJSONObject(j);
        if(item!=null&&item.optString(key,"Sans titre").toLowerCase(Locale.ROOT).contains(needle))matches.add(item);
      }
      runOnUiThread(()->{if(generation!=filterGeneration||!authenticated)return;renderMatches(list,filter,matches);});
    });
  }
  private void renderMatches(LinearLayout list,String filter,ArrayList<JSONObject> matches){
    list.removeAllViews();
    if(matches.isEmpty()){list.addView(text("Aucun résultat",16));return;}
    int totalPages=(matches.size()+PAGE_SIZE-1)/PAGE_SIZE;
    listPage=Math.max(0,Math.min(listPage,totalPages-1));
    list.addView(text("Résultats : "+matches.size()+" • Page "+(listPage+1)+"/"+totalPages,14));
    if(listPage>0)list.addView(button("‹ PAGE PRÉCÉDENTE",()->{listPage--;renderMatches(list,filter,matches);}));
    int end=Math.min(matches.size(),(listPage+1)*PAGE_SIZE);
    for(int j=listPage*PAGE_SIZE;j<end;j++){
      JSONObject item=matches.get(j);TextView row=text(item.optString(currentListKey,"Sans titre"),16);
      row.setBackgroundColor(PANEL);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=8;
      list.addView(row,lp);row.setOnClickListener(v->currentPick.chosen(item));
    }
    if(listPage+1<totalPages)list.addView(button("PAGE SUIVANTE ›",()->{listPage++;renderMatches(list,filter,matches);}));
  }
  private String cleanExt(String ext,String type){String value=ext==null?"":ext.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");if(value.isEmpty())return type.equals("live")?"m3u8":"mp4";return value;}
  private void play(String id,String type,String extension,Runnable back){
    if(!guard())return;if(id.isEmpty()){toast("Flux indisponible");return;}
    release();String folder=type.equals("live")?"live":type.equals("vod")?"movie":"series";
    String suffix=cleanExt(extension,type);
    currentStreamId=id;currentStreamType=type;currentStreamExt=suffix;
    currentMediaType=type;currentMediaKey=type+":"+id;fallbackAttempts=0;fallbackBudget=0;resumeApplied=false;playbackGeneration++;
    alternatives=type.equals("live") ? (suffix.equals("m3u8")?new String[]{"m3u8","ts"}:new String[]{suffix,"m3u8","ts"}) : new String[]{suffix};
    alternateIndex=0;
    activeStreamUrl=streamUrl(folder,id,alternatives[alternateIndex]);
    screen("IPTV PRO2 • Player");playbackScreen=true;resumeOnForeground=true;
    lastPosition=type.equals("live")?0:positions().getLong(positionKey(),0);
    playerBack=back;backAction=()->{release();activeStreamUrl="";resumeOnForeground=false;back.run();};
    root.addView(button("‹ RETOUR",backAction));
    TextView status=text("Chargement du flux…",14);playbackStatus=status;root.addView(status);
    PlayerView view=new PlayerView(this);root.addView(view,new LinearLayout.LayoutParams(-1,0,1));
    root.addView(button("↻ RÉESSAYER LA LECTURE",()->restartPlayback(false)));
    if(type.equals("live"))root.addView(button("ESSAYER UN AUTRE FORMAT",()->restartPlayback(true)));
    player=new ExoPlayer.Builder(this).build();view.setPlayer(player);
    player.addListener(new Player.Listener(){
      @Override public void onPlaybackStateChanged(int state){
        if(state==Player.STATE_READY){status.setText("");if(!resumeApplied&&lastPosition>0&&!"live".equals(currentMediaType)){player.seekTo(lastPosition);lastPosition=0;resumeApplied=true;}}
        else if(state==Player.STATE_BUFFERING)status.setText("Chargement du flux…");
        else if(state==Player.STATE_ENDED){status.setText("Lecture terminée");if(!"live".equals(currentMediaType))positions().edit().remove(positionKey()).apply();}
      }
      @Override public void onPlayerError(PlaybackException error){
        if("live".equals(currentMediaType)&&fallbackBudget<alternatives.length-1){
          fallbackAttempts++;fallbackBudget++;status.setText("Flux indisponible. Essai automatique : "+alternatives[(alternateIndex+1)%alternatives.length]);
          final int generation=playbackGeneration;status.postDelayed(()->{if(player!=null&&playbackScreen&&generation==playbackGeneration)restartPlayback(true);},650);
        }else status.setText("Lecture impossible ("+error.getErrorCodeName()+"). Appuyez sur RÉESSAYER.");
      }
    });
    startMedia(suffix);
  }
  private String streamUrl(String folder,String id,String ext){
    return SERVER+"/"+folder+"/"+pathPart(username)+"/"+pathPart(password)+"/"+pathPart(id)+"."+ext;
  }
  private void startMedia(String ext){
    if(player==null)return;
    MediaItem.Builder media=new MediaItem.Builder().setUri(activeStreamUrl);
    if("m3u8".equals(ext))media.setMimeType(MimeTypes.APPLICATION_M3U8);
    else if("ts".equals(ext))media.setMimeType(MimeTypes.VIDEO_MP2T);
    player.setMediaItem(media.build());player.prepare();player.play();
  }
  private void restartPlayback(boolean alternate){
    if(player==null)return;
    if(!alternate){fallbackAttempts=0;fallbackBudget=0;}
    if(alternate&&alternatives.length>1)alternateIndex=(alternateIndex+1)%alternatives.length;
    String ext=alternatives[alternateIndex];
    playbackGeneration++;
    String folder=currentStreamType.equals("live")?"live":currentStreamType.equals("vod")?"movie":"series";
    activeStreamUrl=streamUrl(folder,currentStreamId,ext);
    if(playbackStatus!=null)playbackStatus.setText("Chargement du flux ("+ext+")…");
    player.stop();startMedia(ext);
  }
  interface Result{void done(JSONObject obj);}
  private void request(String path,Result callback){request(path,callback,()->{});}
  private void request(String path,Result callback,Runnable failure){
    if(!path.startsWith("/player_api.php"))return;
    final int token=screenToken, generation=++requestGeneration;
    final LinearLayout owner=root;
    LinearLayout state=new LinearLayout(this);state.setOrientation(1);
    TextView loading=text("Chargement…",14);state.addView(loading);owner.addView(state);
    Runnable retry=()->{if(token==screenToken&&generation==requestGeneration){owner.removeView(state);request(path,callback,failure);}};
    try{networkExecutor.execute(()->{
      HttpURLConnection connection=null;
      try{
        connection=(HttpURLConnection)new URL(SERVER+path).openConnection();
        connection.setConnectTimeout(12000);connection.setReadTimeout(18000);
        connection.setRequestProperty("Accept","application/json");connection.setInstanceFollowRedirects(false);
        int code=connection.getResponseCode();if(code!=200)throw new IOException("HTTP "+code);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(InputStream in=connection.getInputStream()){
          byte[] buffer=new byte[8192];int n;
          while((n=in.read(buffer))!=-1){
            if(Thread.currentThread().isInterrupted())return;
            out.write(buffer,0,n);
            if(out.size()>32_000_000)throw new IOException("Response too large");
          }
        }
        String raw=out.toString("UTF-8").trim();JSONObject obj;
        if(raw.startsWith("[")){obj=new JSONObject();obj.put("items",new JSONArray(raw));}
        else if(raw.startsWith("{"))obj=new JSONObject(raw);
        else throw new IOException("Invalid JSON");
        runOnUiThread(()->{if(token!=screenToken||generation!=requestGeneration)return;owner.removeView(state);callback.done(obj);});
      }catch(Exception e){
        final String message=e instanceof SocketTimeoutException?"Délai dépassé":e instanceof UnknownHostException?"Serveur introuvable":e instanceof JSONException?"Réponse du serveur invalide":"Connexion impossible";
        runOnUiThread(()->{
          if(token!=screenToken||generation!=requestGeneration)return;
          state.removeAllViews();state.addView(text(message+". Vérifiez votre connexion.",14));
          failure.run();state.addView(button("RÉESSAYER",retry));
        });
      }finally{if(connection!=null)connection.disconnect();}
    });}catch(RejectedExecutionException ex){
      state.removeAllViews();state.addView(text("Trop de requêtes. Réessayez.",14));state.addView(button("RÉESSAYER",retry));failure.run();
    }
  }
  private String enc(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(Exception e){return s;}}
  private String pathPart(String s){return enc(s).replace("+","%20");}
  private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
  private void release(){playbackGeneration++;if(player!=null){savePosition();player.release();player=null;}playbackScreen=false;}
  @Override public void onBackPressed(){if(backAction!=null)backAction.run();else if(authenticated)home();else super.onBackPressed();}
  @Override protected void onStop(){
    if(player!=null&&playbackScreen){lastPosition=player.getCurrentPosition();savePosition();resumeOnForeground=player.isPlaying();player.pause();}
    super.onStop();
  }
  @Override protected void onStart(){super.onStart();if(player!=null&&playbackScreen&&resumeOnForeground){if(!"live".equals(currentMediaType)&&lastPosition>0)player.seekTo(lastPosition);player.play();}}
  @Override protected void onDestroy(){filterGeneration++;filterExecutor.shutdownNow();networkExecutor.shutdownNow();searchHandler.removeCallbacksAndMessages(null);release();super.onDestroy();}
}
