package com.christorng.androidcodextools;

import android.content.Context;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

final class CodexClient {
    static final String CLIENT_ID="app_EMoamEEZ73f0CkXaXp7hrann";
    static final String AUTH_URL="https://auth.openai.com/oauth/authorize";
    static final String TOKEN_URL="https://auth.openai.com/oauth/token";
    static final String USAGE_URL="https://chatgpt.com/backend-api/wham/usage";
    static final String RESPONSES_URL="https://chatgpt.com/backend-api/codex/responses";
    static final String SCOPE="openid profile email offline_access api.connectors.read api.connectors.invoke";
    private final SecureStore store;
    CodexClient(Context c){ store=new SecureStore(c); }

    static final class Pkce {
        final String verifier,challenge,state;
        Pkce(String v,String c,String s){verifier=v;challenge=c;state=s;}
    }
    static final class Window {
        final double usedPercent; final long resetAt;
        Window(double u,long r){usedPercent=u;resetAt=r;}
    }
    static final class Quota {
        final String plan; final boolean allowed,limitReached; final Window primary,secondary;
        Quota(String p,boolean a,boolean l,Window pr,Window se){plan=p;allowed=a;limitReached=l;primary=pr;secondary=se;}
        String summary(){
            String p=primary==null?"5h: inactive":String.format(Locale.US,"5h used %.0f%%, reset %d",primary.usedPercent,primary.resetAt);
            String s=secondary==null?"Weekly: inactive":String.format(Locale.US,"Weekly used %.0f%%, reset %d",secondary.usedPercent,secondary.resetAt);
            return "Plan: "+plan+"\n"+p+"\n"+s+"\nAllowed: "+allowed;
        }
    }

    static Pkce newPkce() throws Exception {
        SecureRandom r=new SecureRandom();
        byte[] vb=new byte[48]; r.nextBytes(vb);
        String v=Base64.encodeToString(vb,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
        String c=Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.US_ASCII)),
                Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
        byte[] sb=new byte[24]; r.nextBytes(sb);
        String s=Base64.encodeToString(sb,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
        return new Pkce(v,c,s);
    }

    static String authorizeUrl(Pkce p,String redirect) throws Exception {
        return AUTH_URL+"?response_type=code&client_id="+enc(CLIENT_ID)+
                "&redirect_uri="+enc(redirect)+"&scope="+enc(SCOPE)+
                "&code_challenge="+enc(p.challenge)+"&code_challenge_method=S256&state="+enc(p.state)+
                "&id_token_add_organizations=true&codex_cli_simplified_flow=true&originator=codex_quota_trigger_android";
    }

    void exchangeCode(String code,String verifier,String redirect) throws Exception {
        saveTokens(new JSONObject(postForm(TOKEN_URL,
                "grant_type=authorization_code&client_id="+enc(CLIENT_ID)+"&code="+enc(code)+
                        "&redirect_uri="+enc(redirect)+"&code_verifier="+enc(verifier))));
    }

    synchronized String validAccessToken() throws Exception {
        String a=store.get("access_token"), e=store.get("expires_at_ms");
        long exp=e==null?0:Long.parseLong(e);
        if(a!=null && System.currentTimeMillis()+300000L<exp)return a;
        refresh();
        a=store.get("access_token");
        if(a==null)throw new IllegalStateException("Not signed in");
        return a;
    }

    synchronized void refresh() throws Exception {
        String r=store.get("refresh_token");
        if(r==null)throw new IllegalStateException("No refresh token; sign in again");
        saveTokens(new JSONObject(postForm(TOKEN_URL,
                "grant_type=refresh_token&client_id="+enc(CLIENT_ID)+"&refresh_token="+enc(r))));
    }

    Quota getQuota() throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(USAGE_URL).openConnection();
        c.setRequestMethod("GET"); c.setConnectTimeout(20000); c.setReadTimeout(20000);
        auth(c);
        JSONObject root=new JSONObject(readResponse(c));
        JSONObject rate=root.optJSONObject("rate_limit");
        return new Quota(root.optString("plan_type","unknown"),
                rate==null||rate.optBoolean("allowed",true),
                rate!=null&&rate.optBoolean("limit_reached",false),
                window(rate==null?null:rate.optJSONObject("primary_window")),
                window(rate==null?null:rate.optJSONObject("secondary_window")));
    }

    int triggerMinimal() throws Exception {
        JSONObject root=new JSONObject();
        root.put("model","gpt-5.6-luna").put("store",false).put("stream",true).put("instructions","Reply .");
        JSONObject txt=new JSONObject().put("type","input_text").put("text",".");
        root.put("input",new JSONArray().put(new JSONObject().put("role","user").put("content",new JSONArray().put(txt))));
        root.put("reasoning",new JSONObject().put("effort","none"));
        root.put("text",new JSONObject().put("verbosity","low"));

        byte[] b=root.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c=(HttpURLConnection)new URL(RESPONSES_URL).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(20000); c.setReadTimeout(60000);
        auth(c);
        c.setRequestProperty("originator","codex-quota-trigger-android");
        c.setRequestProperty("OpenAI-Beta","responses=experimental");
        c.setRequestProperty("Accept","text/event-stream");
        c.setRequestProperty("Content-Type","application/json");
        c.setFixedLengthStreamingMode(b.length);
        try(OutputStream os=c.getOutputStream()){os.write(b);}
        int status=c.getResponseCode();
        String response=readAll(status>=400?c.getErrorStream():c.getInputStream());
        if(status<200||status>=300)throw new IllegalStateException("Trigger HTTP "+status+": "+response);
        return status;
    }

    boolean signedIn(){ return store.get("refresh_token")!=null && store.get("account_id")!=null; }
    void signOut(){store.clear();}

    private void auth(HttpURLConnection c) throws Exception {
        c.setRequestProperty("Authorization","Bearer "+validAccessToken());
        c.setRequestProperty("ChatGPT-Account-Id",store.get("account_id"));
        c.setRequestProperty("User-Agent","android-codex-tools/0.1");
    }

    private void saveTokens(JSONObject j) throws Exception {
        String a=j.optString("access_token",null), r=j.optString("refresh_token",null), id=j.optString("id_token",null);
        if(a!=null)store.put("access_token",a);
        if(r!=null)store.put("refresh_token",r);
        if(id!=null){
            store.put("id_token",id);
            String account=extractAccountId(id);
            if(account!=null)store.put("account_id",account);
        }
        store.put("expires_at_ms",Long.toString(System.currentTimeMillis()+j.optLong("expires_in",3600)*1000L));
        if(store.get("account_id")==null)throw new IllegalStateException("ChatGPT account id not found in token");
    }

    private static String extractAccountId(String jwt){
        try{
            String[] p=jwt.split("\\.");
            String payload=new String(Base64.decode(p[1],Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING),StandardCharsets.UTF_8);
            JSONObject root=new JSONObject(payload);
            JSONObject auth=root.optJSONObject("https://api.openai.com/auth");
            if(auth!=null){
                String x=auth.optString("chatgpt_account_id",null);
                if(x!=null&&!x.isEmpty())return x;
            }
            String x=root.optString("chatgpt_account_id",null);
            return x==null||x.isEmpty()?null:x;
        }catch(Exception e){return null;}
    }

    private static Window window(JSONObject w){ return w==null?null:new Window(w.optDouble("used_percent",0),w.optLong("reset_at",0)); }

    private static String postForm(String url,String body) throws Exception {
        byte[] b=body.getBytes(StandardCharsets.UTF_8);
        Exception last=null;
        for(int attempt=1;attempt<=3;attempt++){
            try{
                HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
                c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(20000); c.setReadTimeout(20000);
                c.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
                c.setRequestProperty("Accept","application/json"); c.setFixedLengthStreamingMode(b.length);
                try(OutputStream os=c.getOutputStream()){os.write(b);}
                return readResponse(c);
            }catch(UnknownHostException e){
                last=e;
                if(attempt<3) Thread.sleep(1500L*attempt);
            }
        }
        throw new UnknownHostException("Android system DNS cannot resolve auth.openai.com after retries: "+(last==null?"unknown":last.getMessage()));
    }

    private static String readResponse(HttpURLConnection c) throws Exception {
        int s=c.getResponseCode(); String t=readAll(s>=400?c.getErrorStream():c.getInputStream());
        if(s<200||s>=300)throw new IllegalStateException("HTTP "+s+": "+t);
        return t;
    }
    private static String readAll(InputStream is) throws Exception {
        if(is==null)return "";
        StringBuilder sb=new StringBuilder();
        try(BufferedReader br=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8))){
            String l; while((l=br.readLine())!=null)sb.append(l).append('\n');
        }
        return sb.toString();
    }
    private static String enc(String s) throws Exception { return URLEncoder.encode(s,"UTF-8"); }
}
