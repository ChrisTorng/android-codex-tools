package com.christorng.androidcodextools;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MainActivity extends Activity {
    private TextView status; private EditText ntfy;

    @Override protected void onCreate(Bundle b){super.onCreate(b);buildUi();refreshUi();}

    private void buildUi(){
        int pad=(int)(16*getResources().getDisplayMetrics().density);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(pad,pad,pad,pad);
        TextView title=new TextView(this); title.setText("Android Codex Tools"); title.setTextSize(24); title.setTypeface(Typeface.DEFAULT_BOLD); root.addView(title);
        status=new TextView(this); status.setTextIsSelectable(true); status.setPadding(0,pad,0,pad); root.addView(status);
        ntfy=new EditText(this); ntfy.setHint("ntfy topic URL"); ntfy.setSingleLine(true);
        ntfy.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        ntfy.setText(Scheduler.prefs(this).getString(Scheduler.KEY_NTFY,"https://ntfy.sh/codex-8b7a238e0815ab41ffe56082eb6a7b21")); root.addView(ntfy);
        root.addView(btn("Save ntfy URL",v->{Scheduler.prefs(this).edit().putString(Scheduler.KEY_NTFY,ntfy.getText().toString().trim()).apply();refreshUi();}));
        root.addView(btn("Sign in with ChatGPT",v->startLogin()));
        root.addView(btn("Check now",v->runAsync(false)));
        root.addView(btn("Trigger test (uses quota)",v->runAsync(true)));
        root.addView(btn("Enable scheduler",v->{Scheduler.setEnabled(this,true);if(!Scheduler.canExact(this))try{startActivity(Scheduler.exactSettings(this));}catch(Exception ignored){}runAsync(false);}));
        root.addView(btn("Disable scheduler",v->{Scheduler.setEnabled(this,false);refreshUi();}));
        root.addView(btn("Exact alarm settings",v->{try{startActivity(Scheduler.exactSettings(this));}catch(Exception ignored){}}));
        root.addView(btn("Sign out",v->{new CodexClient(this).signOut();Scheduler.setEnabled(this,false);refreshUi();}));
        ScrollView s=new ScrollView(this); s.addView(root); setContentView(s);
    }

    private Button btn(String t,View.OnClickListener l){Button b=new Button(this);b.setText(t);b.setOnClickListener(l);return b;}

    private void refreshUi(){
        CodexClient c=new CodexClient(this);
        status.setText("Account: "+(c.signedIn()?"signed in":"not signed in")+
                "\nScheduler: "+(Scheduler.enabled(this)?"enabled":"disabled")+
                "\nExact alarm: "+(Scheduler.canExact(this)?"granted":"not granted")+
                "\nNext alarm: "+Scheduler.formattedNext(this)+"\n\nLast status:\n"+Scheduler.last(this));
    }

    private void runAsync(boolean force){
        status.setText("Running...");
        new Thread(()->{Scheduler.runCycle(getApplicationContext(),force);runOnUiThread(this::refreshUi);},"codex-manual").start();
    }

    private void startLogin(){
        status.setText("Starting OAuth...");
        new Thread(()->{
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
                String redirect="http://127.0.0.1:"+server.getLocalPort()+"/auth/callback";
                CodexClient.Pkce p=CodexClient.newPkce();
                String url=CodexClient.authorizeUrl(p,redirect);
                runOnUiThread(()->{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));status.setText("Waiting for browser callback...");});
                try(Socket socket=server.accept()){
                    BufferedReader br=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                    String first=br.readLine(); if(first==null||!first.startsWith("GET "))throw new IllegalStateException("Invalid callback");
                    String path=first.split(" ")[1]; String line; while((line=br.readLine())!=null&&!line.isEmpty()){}
                    URI uri=new URI("http://127.0.0.1"+path); Map<String,String> q=parseQuery(uri.getRawQuery());
                    OutputStream os=socket.getOutputStream();
                    String html;
                    try {
                        if(q.get("error")!=null)throw new IllegalStateException("OAuth error: "+q.get("error"));
                        String callbackState=q.get("state");
                        String acceptedState=p.state+".onboarding_entrypoint=life_sciences";
                        if(q.get("code")==null || !(p.state.equals(callbackState)||acceptedState.equals(callbackState)))
                            throw new IllegalStateException("OAuth state/code mismatch");
                        new CodexClient(this).exchangeCode(q.get("code"),p.verifier,redirect);
                        Scheduler.prefs(this).edit().putString(Scheduler.KEY_LAST,"Login successful").apply();
                        html="<html><body><h2>Codex login successful</h2><p>You can return to the app.</p></body></html>";
                    } catch(Exception loginError) {
                        String msg=String.valueOf(loginError.getMessage());
                        Scheduler.prefs(this).edit().putString(Scheduler.KEY_LAST,"Login ERROR: "+msg).apply();
                        html="<html><body><h2>Codex login failed</h2><pre>"+escape(msg)+"</pre><p>Return to the app and report this message.</p></body></html>";
                    }
                    byte[] body=html.getBytes(StandardCharsets.UTF_8);
                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    os.write(body); os.flush();
                }
            }catch(Exception e){Scheduler.prefs(this).edit().putString(Scheduler.KEY_LAST,"Login server ERROR: "+e.getMessage()).apply();}
            runOnUiThread(this::refreshUi);
        },"codex-oauth").start();
    }

    private static String escape(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}

    private static Map<String,String> parseQuery(String raw) throws Exception{
        Map<String,String> m=new HashMap<>(); if(raw==null)return m;
        for(String pair:raw.split("&")){String[] kv=pair.split("=",2);m.put(URLDecoder.decode(kv[0],"UTF-8"),kv.length>1?URLDecoder.decode(kv[1],"UTF-8"):"");}
        return m;
    }
}
