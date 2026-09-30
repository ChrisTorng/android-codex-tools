package com.christorng.androidcodextools;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String DEFAULT_NTFY="https://ntfy.sh/codex-8b7a238e0815ab41ffe56082eb6a7b21";

    private FrameLayout pageHost;
    private View statusPage;
    private View schedulePage;
    private View settingsPage;

    private TextView accountValue;
    private TextView primaryValue;
    private TextView weeklyValue;
    private TextView scheduleValue;
    private TextView lastValue;

    private Spinner modeSpinner;
    private TextView nextScheduleValue;
    private LinearLayout customSection;
    private LinearLayout rulesContainer;
    private EditText overridesEdit;
    private final List<ScheduleConfig.Rule> draftRules=new ArrayList<>();

    private TextView settingsAccountValue;
    private TextView exactAlarmValue;
    private EditText ntfyEdit;
    private TextView versionValue;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        buildUi();
        loadScheduleDraft();
        refreshUi();
    }

    @Override protected void onResume(){
        super.onResume();
        refreshUi();
    }

    private void buildUi(){
        final int side=dp(16);
        LinearLayout shell=new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Color.rgb(250,250,250));
        shell.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView title=new TextView(this);
        title.setText("Codex 配額排程");
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(35,35,35));
        title.setPadding(side,dp(14),side,dp(8));
        shell.addView(title,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout tabs=new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(side,0,side,dp(8));
        Button tabStatus=tabButton("狀態");
        Button tabSchedule=tabButton("排程");
        Button tabSettings=tabButton("設定");
        tabs.addView(tabStatus,weight());
        tabs.addView(tabSchedule,weight());
        tabs.addView(tabSettings,weight());
        shell.addView(tabs,new LinearLayout.LayoutParams(-1,-2));

        pageHost=new FrameLayout(this);
        statusPage=buildStatusPage();
        schedulePage=buildSchedulePage();
        settingsPage=buildSettingsPage();
        pageHost.addView(statusPage);
        pageHost.addView(schedulePage);
        pageHost.addView(settingsPage);
        shell.addView(pageHost,new LinearLayout.LayoutParams(-1,0,1));

        tabStatus.setOnClickListener(v->showPage(0));
        tabSchedule.setOnClickListener(v->showPage(1));
        tabSettings.setOnClickListener(v->showPage(2));
        setContentView(shell);
        showPage(0);
    }

    private View buildStatusPage(){
        LinearLayout content=pageContent();

        accountValue=valueText();
        primaryValue=valueText();
        weeklyValue=valueText();
        scheduleValue=valueText();
        lastValue=valueText();
        lastValue.setMaxLines(4);
        lastValue.setEllipsize(TextUtils.TruncateAt.END);

        content.addView(card("帳號",accountValue));
        content.addView(card("5 小時配額",primaryValue));
        content.addView(card("週配額",weeklyValue));
        content.addView(card("下一次排程",scheduleValue));

        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh=actionButton("更新配額");
        Button trigger=actionButton("立即觸發");
        actions.addView(refresh,weightWithMargin(false));
        actions.addView(trigger,weightWithMargin(true));
        content.addView(actions,new LinearLayout.LayoutParams(-1,-2));

        content.addView(card("最近事件",lastValue));

        refresh.setOnClickListener(v->runCheck());
        trigger.setOnClickListener(v->confirmTrigger());

        return scroll(content);
    }

    private View buildSchedulePage(){
        LinearLayout content=pageContent();

        TextView modeLabel=sectionTitle("排程模式");
        content.addView(modeLabel);

        modeSpinner=new Spinner(this);
        String[] modes={"關閉","配額重置後自動觸發","自訂週排程"};
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,modes);
        modeSpinner.setAdapter(adapter);
        content.addView(modeSpinner,new LinearLayout.LayoutParams(-1,dp(52)));

        nextScheduleValue=valueText();
        content.addView(card("目前下一次觸發",nextScheduleValue));

        customSection=new LinearLayout(this);
        customSection.setOrientation(LinearLayout.VERTICAL);

        TextView help=bodyText(
                "每一列可選星期與時間；可建立多列，所以同一天能有多個觸發時間。"+
                "某天沒有任何規則就不會觸發。");
        customSection.addView(help);

        rulesContainer=new LinearLayout(this);
        rulesContainer.setOrientation(LinearLayout.VERTICAL);
        customSection.addView(rulesContainer,new LinearLayout.LayoutParams(-1,-2));

        Button addRule=actionButton("＋ 新增時段");
        customSection.addView(addRule,new LinearLayout.LayoutParams(-1,dp(50)));
        addRule.setOnClickListener(v->{
            draftRules.add(new ScheduleConfig.Rule());
            renderRules();
        });

        customSection.addView(sectionTitle("特殊日期／假日覆寫"));
        TextView overrideHelp=bodyText(
                "指定日期後，當天會完全取代每週規則。每行格式：\n"+
                "2026-10-10=off\n"+
                "2026-10-25=09:00,14:00\n"+
                "可用 off 表示當天完全不觸發。");
        customSection.addView(overrideHelp);

        overridesEdit=new EditText(this);
        overridesEdit.setMinLines(4);
        overridesEdit.setGravity(Gravity.TOP|Gravity.START);
        overridesEdit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        overridesEdit.setHint("YYYY-MM-DD=off 或 YYYY-MM-DD=HH:mm,HH:mm");
        customSection.addView(overridesEdit,new LinearLayout.LayoutParams(-1,-2));

        content.addView(customSection,new LinearLayout.LayoutParams(-1,-2));

        Button save=actionButton("儲存並套用排程");
        LinearLayout.LayoutParams saveLp=new LinearLayout.LayoutParams(-1,dp(54));
        saveLp.setMargins(0,dp(14),0,dp(12));
        content.addView(save,saveLp);
        save.setOnClickListener(v->saveSchedule());

        modeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
                customSection.setVisibility(pos==2?View.VISIBLE:View.GONE);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
        });

        return scroll(content);
    }

    private View buildSettingsPage(){
        LinearLayout content=pageContent();

        settingsAccountValue=valueText();
        content.addView(card("ChatGPT 帳號",settingsAccountValue));

        Button login=actionButton("登入 ChatGPT");
        content.addView(login,new LinearLayout.LayoutParams(-1,dp(52)));
        login.setOnClickListener(v->startLogin());

        Button logout=actionButton("登出");
        LinearLayout.LayoutParams logoutLp=new LinearLayout.LayoutParams(-1,dp(48));
        logoutLp.setMargins(0,dp(8),0,dp(12));
        content.addView(logout,logoutLp);
        logout.setOnClickListener(v->{
            new CodexClient(this).signOut();
            Scheduler.setMode(this,Scheduler.MODE_OFF);
            Scheduler.note(this,"已登出；排程已關閉");
            refreshUi();
        });

        content.addView(sectionTitle("ntfy 通知"));
        ntfyEdit=new EditText(this);
        ntfyEdit.setSingleLine(true);
        ntfyEdit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        ntfyEdit.setHint("https://ntfy.sh/你的-topic");
        content.addView(ntfyEdit,new LinearLayout.LayoutParams(-1,dp(54)));
        Button saveNtfy=actionButton("儲存 ntfy URL");
        content.addView(saveNtfy,new LinearLayout.LayoutParams(-1,dp(48)));
        saveNtfy.setOnClickListener(v->{
            Scheduler.prefs(this).edit().putString(Scheduler.KEY_NTFY,ntfyEdit.getText().toString().trim()).apply();
            Toast.makeText(this,"已儲存",Toast.LENGTH_SHORT).show();
        });

        exactAlarmValue=valueText();
        content.addView(card("Android 精準鬧鐘",exactAlarmValue));
        Button exact=actionButton("開啟精準鬧鐘設定");
        content.addView(exact,new LinearLayout.LayoutParams(-1,dp(50)));
        exact.setOnClickListener(v->{
            try{startActivity(Scheduler.exactSettings(this));}
            catch(Exception e){Toast.makeText(this,"無法開啟系統設定",Toast.LENGTH_SHORT).show();}
        });

        TextView networkInfo=bodyText(
                "連線策略：一般 Android DNS／目前網路解析失敗時，會自動使用 DoH fallback。"+
                "這是目前登入在 Tailscale 開啟或關閉時都能工作的必要處理。");
        content.addView(card("網路",networkInfo));

        versionValue=valueText();
        content.addView(card("版本",versionValue));

        return scroll(content);
    }

    private void showPage(int index){
        statusPage.setVisibility(index==0?View.VISIBLE:View.GONE);
        schedulePage.setVisibility(index==1?View.VISIBLE:View.GONE);
        settingsPage.setVisibility(index==2?View.VISIBLE:View.GONE);
    }

    private void loadScheduleDraft(){
        draftRules.clear();
        for(ScheduleConfig.Rule r:ScheduleConfig.loadRules(this))draftRules.add(r.copy());
        if(overridesEdit!=null)overridesEdit.setText(ScheduleConfig.loadOverrides(this));

        String mode=Scheduler.mode(this);
        int pos=Scheduler.MODE_AUTO.equals(mode)?1:Scheduler.MODE_CUSTOM.equals(mode)?2:0;
        modeSpinner.setSelection(pos);
        customSection.setVisibility(pos==2?View.VISIBLE:View.GONE);
        renderRules();
    }

    private void renderRules(){
        if(rulesContainer==null)return;
        rulesContainer.removeAllViews();

        if(draftRules.isEmpty()){
            TextView empty=bodyText("尚未新增任何自訂時段。");
            empty.setPadding(0,dp(10),0,dp(10));
            rulesContainer.addView(empty);
            return;
        }

        for(int index=0;index<draftRules.size();index++){
            final ScheduleConfig.Rule rule=draftRules.get(index);
            LinearLayout box=new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(12),dp(10),dp(12),dp(10));
            box.setBackground(roundRect(Color.WHITE,dp(14),Color.rgb(225,225,225)));

            LinearLayout top=new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            Switch enabled=new Switch(this);
            enabled.setText("啟用");
            enabled.setChecked(rule.enabled);
            enabled.setOnCheckedChangeListener((b,checked)->rule.enabled=checked);
            top.addView(enabled,new LinearLayout.LayoutParams(0,dp(48),1));

            Button time=new Button(this);
            time.setText(rule.timeText());
            time.setOnClickListener(v->new TimePickerDialog(this,(TimePicker view,int h,int m)->{
                rule.hour=h;
                rule.minute=m;
                time.setText(rule.timeText());
            },rule.hour,rule.minute,true).show());
            top.addView(time,new LinearLayout.LayoutParams(dp(100),dp(48)));

            Button delete=new Button(this);
            delete.setText("刪除");
            delete.setOnClickListener(v->{
                draftRules.remove(rule);
                renderRules();
            });
            top.addView(delete,new LinearLayout.LayoutParams(dp(88),dp(48)));
            box.addView(top);

            LinearLayout days=new LinearLayout(this);
            days.setOrientation(LinearLayout.HORIZONTAL);
            String[] names={"一","二","三","四","五","六","日"};
            for(int d=0;d<7;d++){
                final int day=d;
                ToggleButton t=new ToggleButton(this);
                t.setTextOn(names[d]);
                t.setTextOff(names[d]);
                t.setText(names[d]);
                t.setTextSize(12);
                t.setMinWidth(0);
                t.setPadding(0,0,0,0);
                t.setChecked(rule.days[d]);
                t.setOnCheckedChangeListener((b,checked)->rule.days[day]=checked);
                days.addView(t,new LinearLayout.LayoutParams(0,dp(44),1));
            }
            box.addView(days);

            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
            lp.setMargins(0,dp(6),0,dp(6));
            rulesContainer.addView(box,lp);
        }
    }

    private void saveSchedule(){
        try{
            ScheduleConfig.saveRules(this,draftRules,overridesEdit.getText().toString());
            String mode=modeSpinner.getSelectedItemPosition()==1?Scheduler.MODE_AUTO:
                    modeSpinner.getSelectedItemPosition()==2?Scheduler.MODE_CUSTOM:Scheduler.MODE_OFF;
            Scheduler.setMode(this,mode);
            Scheduler.note(this,"排程設定已儲存");
            if(!Scheduler.MODE_OFF.equals(mode)&&!Scheduler.canExact(this)){
                try{startActivity(Scheduler.exactSettings(this));}
                catch(Exception ignored){}
            }
            Toast.makeText(this,"排程已套用",Toast.LENGTH_SHORT).show();
            refreshUi();
        }catch(Exception e){
            new AlertDialog.Builder(this)
                    .setTitle("排程格式錯誤")
                    .setMessage(e.getMessage())
                    .setPositiveButton("確定",null)
                    .show();
        }
    }

    private void refreshUi(){
        CodexClient client=new CodexClient(this);
        Scheduler.Snapshot q=Scheduler.snapshot(this);
        boolean signed=client.signedIn();

        accountValue.setText(signed?"已登入 · "+q.plan:"尚未登入");
        settingsAccountValue.setText(signed?"已登入":"尚未登入");

        if(q.lastCheckMs<=0){
            primaryValue.setText("尚未取得配額資料");
            weeklyValue.setText("尚未取得配額資料");
        }else{
            if(q.primaryActive){
                double remaining=Math.max(0,100-q.primaryUsed);
                primaryValue.setText(String.format(Locale.TAIWAN,
                        "已使用 %.0f%% · 剩餘 %.0f%%\n重置：%s",
                        q.primaryUsed,remaining,Scheduler.formatTime(q.primaryResetMs)));
            }else{
                primaryValue.setText("目前沒有 5 小時視窗");
            }

            if(q.secondaryActive){
                double remaining=Math.max(0,100-q.secondaryUsed);
                weeklyValue.setText(String.format(Locale.TAIWAN,
                        "已使用 %.0f%% · 剩餘 %.0f%%\n重置：%s",
                        q.secondaryUsed,remaining,Scheduler.formatTime(q.secondaryResetMs)));
            }else{
                weeklyValue.setText("目前沒有週配額資料");
            }
        }

        String modeName=Scheduler.MODE_AUTO.equals(Scheduler.mode(this))?"配額重置後自動觸發":
                Scheduler.MODE_CUSTOM.equals(Scheduler.mode(this))?"自訂週排程":"已關閉";
        scheduleValue.setText(modeName+"\n"+Scheduler.formattedNext(this));
        nextScheduleValue.setText(Scheduler.formattedNext(this));
        lastValue.setText(Scheduler.last(this));

        exactAlarmValue.setText(Scheduler.canExact(this)?
                "已允許精準鬧鐘；可在 Doze 狀態準時喚醒 App。":
                "尚未允許精準鬧鐘；排程時間可能不精準。");

        if(ntfyEdit!=null && !ntfyEdit.hasFocus()){
            ntfyEdit.setText(Scheduler.prefs(this).getString(Scheduler.KEY_NTFY,DEFAULT_NTFY));
        }

        if(versionValue!=null){
            try{
                PackageInfo p=getPackageManager().getPackageInfo(getPackageName(),0);
                versionValue.setText(p.versionName+" · versionCode "+p.getLongVersionCode());
            }catch(Exception e){versionValue.setText("—");}
        }
    }

    private void runCheck(){
        Toast.makeText(this,"正在更新配額…",Toast.LENGTH_SHORT).show();
        new Thread(()->{
            Scheduler.checkNow(getApplicationContext());
            runOnUiThread(this::refreshUi);
        },"codex-check").start();
    }

    private void confirmTrigger(){
        new AlertDialog.Builder(this)
                .setTitle("立即觸發 Codex？")
                .setMessage("這會送出一個最小 Codex request，會消耗配額；若目前沒有 5 小時視窗，通常會建立新的視窗。")
                .setNegativeButton("取消",null)
                .setPositiveButton("觸發",(d,w)->runManualTrigger())
                .show();
    }

    private void runManualTrigger(){
        Toast.makeText(this,"正在觸發…",Toast.LENGTH_SHORT).show();
        new Thread(()->{
            Scheduler.manualTrigger(getApplicationContext());
            runOnUiThread(this::refreshUi);
        },"codex-trigger").start();
    }

    private void startLogin(){
        Scheduler.note(this,"等待瀏覽器登入…");
        refreshUi();

        new Thread(()->{
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
                server.setSoTimeout(5*60_000);
                String redirect="http://127.0.0.1:"+server.getLocalPort()+"/auth/callback";
                CodexClient.Pkce p=CodexClient.newPkce();
                String url=CodexClient.authorizeUrl(p,redirect);
                runOnUiThread(()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))));

                String code;
                try(Socket socket=server.accept()){
                    BufferedReader br=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                    String first=br.readLine();
                    if(first==null||!first.startsWith("GET "))throw new IllegalStateException("Invalid callback");
                    String path=first.split(" ")[1];
                    String line;
                    while((line=br.readLine())!=null&&!line.isEmpty()){}

                    URI uri=new URI("http://127.0.0.1"+path);
                    Map<String,String> query=parseQuery(uri.getRawQuery());
                    if(query.get("error")!=null){
                        String msg="OAuth error: "+query.get("error");
                        writeHtml(socket,"<h2>Codex login failed</h2><pre>"+escape(msg)+"</pre>");
                        throw new IllegalStateException(msg);
                    }

                    String callbackState=query.get("state");
                    String acceptedState=p.state+".onboarding_entrypoint=life_sciences";
                    code=query.get("code");
                    if(code==null||!(p.state.equals(callbackState)||acceptedState.equals(callbackState))){
                        String msg="OAuth state/code mismatch";
                        writeHtml(socket,"<h2>Codex login failed</h2><pre>"+escape(msg)+"</pre>");
                        throw new IllegalStateException(msg);
                    }

                    writeHtml(socket,
                            "<h2>Authorization received</h2>"+
                            "<p>You can return to Android Codex Tools while it finishes signing in.</p>");
                }

                Scheduler.note(this,"已收到授權，正在取得 token…");
                runOnUiThread(this::refreshUi);

                try{
                    new CodexClient(this).exchangeCode(code,p.verifier,redirect);
                    Scheduler.note(this,"登入成功");
                    Scheduler.checkNow(getApplicationContext());
                }catch(Exception loginError){
                    Scheduler.note(this,"登入失敗: "+loginError.getClass().getSimpleName()+": "+loginError.getMessage());
                }
            }catch(Exception e){
                Scheduler.note(this,"登入流程失敗: "+e.getClass().getSimpleName()+": "+e.getMessage());
            }
            runOnUiThread(this::refreshUi);
        },"codex-oauth").start();
    }

    private LinearLayout pageContent(){
        LinearLayout c=new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16),dp(8),dp(16),dp(28));
        return c;
    }

    private ScrollView scroll(View child){
        ScrollView s=new ScrollView(this);
        s.setFillViewport(true);
        s.setClipToPadding(false);
        s.addView(child,new ScrollView.LayoutParams(-1,-2));
        return s;
    }

    private View card(String title,TextView value){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14),dp(12),dp(14),dp(12));
        box.setBackground(roundRect(Color.WHITE,dp(16),Color.rgb(228,228,228)));

        TextView t=new TextView(this);
        t.setText(title);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.rgb(95,95,95));
        box.addView(t);

        value.setPadding(0,dp(4),0,0);
        box.addView(value,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,dp(6),0,dp(6));
        box.setLayoutParams(lp);
        return box;
    }

    private TextView valueText(){
        TextView t=new TextView(this);
        t.setTextSize(18);
        t.setTextColor(Color.rgb(35,35,35));
        t.setTextIsSelectable(true);
        t.setLineSpacing(0,1.08f);
        return t;
    }

    private TextView bodyText(String text){
        TextView t=new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(Color.rgb(75,75,75));
        t.setLineSpacing(0,1.12f);
        t.setPadding(0,dp(6),0,dp(8));
        return t;
    }

    private TextView sectionTitle(String text){
        TextView t=new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.rgb(45,45,45));
        t.setPadding(0,dp(10),0,dp(6));
        return t;
    }

    private Button tabButton(String text){
        Button b=new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private Button actionButton(String text){
        Button b=new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams weight(){
        return new LinearLayout.LayoutParams(0,dp(48),1);
    }

    private LinearLayout.LayoutParams weightWithMargin(boolean left){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(52),1);
        if(left)lp.setMargins(dp(4),0,0,0);
        else lp.setMargins(0,0,dp(4),0);
        return lp;
    }

    private GradientDrawable roundRect(int fill,float radius,int stroke){
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        d.setStroke(dp(1),stroke);
        return d;
    }

    private int dp(int value){
        return (int)(value*getResources().getDisplayMetrics().density+0.5f);
    }

    private static void writeHtml(Socket socket,String bodyHtml) throws Exception{
        String html="<html><head><meta name='viewport' content='width=device-width, initial-scale=1'>"+
                "<style>body{font-family:sans-serif;padding:24px;line-height:1.5}pre{white-space:pre-wrap;word-break:break-word}</style>"+
                "</head><body>"+bodyHtml+"</body></html>";
        byte[] body=html.getBytes(StandardCharsets.UTF_8);
        OutputStream os=socket.getOutputStream();
        os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"+
                "Content-Length: "+body.length+"\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        os.write(body);
        os.flush();
    }

    private static String escape(String s){
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    private static Map<String,String> parseQuery(String raw) throws Exception{
        Map<String,String> map=new HashMap<>();
        if(raw==null)return map;
        for(String pair:raw.split("&")){
            String[] kv=pair.split("=",2);
            map.put(URLDecoder.decode(kv[0],"UTF-8"),
                    kv.length>1?URLDecoder.decode(kv[1],"UTF-8"):"");
        }
        return map;
    }
}
