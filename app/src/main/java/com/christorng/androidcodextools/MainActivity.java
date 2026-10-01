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
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final String DEFAULT_NTFY="https://ntfy.sh/codex-8b7a238e0815ab41ffe56082eb6a7b21";
    private static final int ACCENT=Color.rgb(47,128,237);

    private View statusPage,schedulePage,settingsPage;

    private TextView accountLine;
    private TextView primaryPercent,primaryMeta;
    private TextView weeklyPercent,weeklyMeta;
    private QuotaProgressView primaryBar,weeklyBar;
    private TextView scheduleSummary,lastEvent;

    private Spinner modeSpinner;
    private TextView nextAlarmText,previewText,weeklySummary,todaySummary,tomorrowSummary;

    private TextView settingsAccount,exactAlarmText,versionText;
    private EditText ntfyEdit;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        buildUi();
        refreshAll();
    }

    @Override protected void onResume(){
        super.onResume();
        refreshAll();
    }

    private void buildUi(){
        LinearLayout shell=new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Color.rgb(247,248,250));
        shell.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16),dp(10),dp(10),dp(6));
        TextView title=new TextView(this);
        title.setText("Codex 配額");
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.rgb(30,34,40));
        header.addView(title,new LinearLayout.LayoutParams(0,dp(44),1));
        Button refreshSmall=smallButton("↻");
        refreshSmall.setContentDescription("更新配額");
        refreshSmall.setOnClickListener(v->runCheck());
        header.addView(refreshSmall,new LinearLayout.LayoutParams(dp(52),dp(42)));
        shell.addView(header);

        LinearLayout tabs=new LinearLayout(this);
        tabs.setPadding(dp(12),0,dp(12),dp(6));
        Button s=tabButton("狀態");
        Button p=tabButton("排程");
        Button g=tabButton("設定");
        tabs.addView(s,weight(dp(44)));
        tabs.addView(p,weight(dp(44)));
        tabs.addView(g,weight(dp(44)));
        shell.addView(tabs);

        FrameLayout host=new FrameLayout(this);
        statusPage=buildStatusPage();
        schedulePage=buildSchedulePage();
        settingsPage=buildSettingsPage();
        host.addView(statusPage);
        host.addView(schedulePage);
        host.addView(settingsPage);
        shell.addView(host,new LinearLayout.LayoutParams(-1,0,1));

        s.setOnClickListener(v->showPage(0));
        p.setOnClickListener(v->showPage(1));
        g.setOnClickListener(v->showPage(2));
        setContentView(shell);
        showPage(0);
    }

    private View buildStatusPage(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14),dp(4),dp(14),dp(8));

        accountLine=new TextView(this);
        accountLine.setTextSize(13);
        accountLine.setTextColor(Color.rgb(95,100,110));
        accountLine.setPadding(dp(2),0,0,dp(4));
        root.addView(accountLine);

        primaryPercent=new TextView(this);
        primaryMeta=new TextView(this);
        primaryBar=new QuotaProgressView(this);
        root.addView(quotaCard("5 小時",primaryPercent,primaryBar,primaryMeta));

        weeklyPercent=new TextView(this);
        weeklyMeta=new TextView(this);
        weeklyBar=new QuotaProgressView(this);
        root.addView(quotaCard("週配額",weeklyPercent,weeklyBar,weeklyMeta));

        scheduleSummary=valueText(15);
        root.addView(compactCard("下一次排程",scheduleSummary));

        LinearLayout actions=new LinearLayout(this);
        Button check=actionButton("更新配額");
        Button trigger=actionButton("立即觸發");
        actions.addView(check,half(false));
        actions.addView(trigger,half(true));
        root.addView(actions,new LinearLayout.LayoutParams(-1,dp(48)));
        check.setOnClickListener(v->runCheck());
        trigger.setOnClickListener(v->confirmTrigger());

        lastEvent=valueText(13);
        lastEvent.setMaxLines(2);
        root.addView(compactCard("最近事件",lastEvent));

        return root;
    }

    private View buildSchedulePage(){
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14),dp(4),dp(14),dp(24));

        content.addView(sectionTitle("模式"));
        LinearLayout modeRow=new LinearLayout(this);
        modeSpinner=new Spinner(this);
        modeSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
                new String[]{"關閉","配額重置後自動觸發","錨點＋自動接續"}));
        modeRow.addView(modeSpinner,new LinearLayout.LayoutParams(0,dp(50),1));
        Button apply=smallButton("套用");
        modeRow.addView(apply,new LinearLayout.LayoutParams(dp(84),dp(48)));
        content.addView(modeRow);
        apply.setOnClickListener(v->applyMode());

        nextAlarmText=valueText(15);
        content.addView(compactCard("實際下一個 Alarm",nextAlarmText));

        previewText=valueText(14);
        content.addView(compactCard("預估",previewText));

        TextView note=bodyText("錨點到時若距 5h reset ≤ 60 秒，會等到 reset 後 15 秒再觸發；自動接續永遠依 backend 實際 reset 排下一次，不累積秒差。");
        content.addView(note);

        weeklySummary=valueText(14);
        Button editWeekly=actionButton("編輯每週預設");
        content.addView(summaryEditorCard("每週預設",weeklySummary,editWeekly));
        editWeekly.setOnClickListener(v->editWeekly());

        todaySummary=valueText(14);
        Button editToday=actionButton("編輯今天");
        content.addView(summaryEditorCard("今天",todaySummary,editToday));
        editToday.setOnClickListener(v->editOverride(LocalDate.now(),"今天"));

        tomorrowSummary=valueText(14);
        Button editTomorrow=actionButton("編輯明天");
        content.addView(summaryEditorCard("明天",tomorrowSummary,editTomorrow));
        editTomorrow.setOnClickListener(v->editOverride(LocalDate.now().plusDays(1),"明天"));

        return scroll(content);
    }

    private View buildSettingsPage(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14),dp(4),dp(14),dp(8));

        settingsAccount=valueText(15);
        root.addView(compactCard("ChatGPT",settingsAccount));

        LinearLayout authRow=new LinearLayout(this);
        Button login=actionButton("登入");
        Button logout=actionButton("登出");
        authRow.addView(login,half(false));
        authRow.addView(logout,half(true));
        root.addView(authRow,new LinearLayout.LayoutParams(-1,dp(46)));
        login.setOnClickListener(v->startLogin());
        logout.setOnClickListener(v->{
            new CodexClient(this).signOut();
            Scheduler.setMode(this,Scheduler.MODE_OFF);
            Scheduler.note(this,"已登出；排程已關閉");
            refreshAll();
        });

        TextView ntfyLabel=sectionTitle("ntfy");
        ntfyLabel.setPadding(0,dp(8),0,0);
        root.addView(ntfyLabel);
        LinearLayout ntfyRow=new LinearLayout(this);
        ntfyEdit=new EditText(this);
        ntfyEdit.setSingleLine(true);
        ntfyEdit.setTextSize(14);
        ntfyEdit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        Button save=smallButton("儲存");
        ntfyRow.addView(ntfyEdit,new LinearLayout.LayoutParams(0,dp(48),1));
        ntfyRow.addView(save,new LinearLayout.LayoutParams(dp(76),dp(46)));
        root.addView(ntfyRow);
        save.setOnClickListener(v->{
            Scheduler.prefs(this).edit().putString(Scheduler.KEY_NTFY,ntfyEdit.getText().toString().trim()).apply();
            toast("已儲存");
        });

        exactAlarmText=valueText(14);
        Button exact=actionButton("精準鬧鐘設定");
        root.addView(summaryEditorCard("Android Alarm",exactAlarmText,exact));
        exact.setOnClickListener(v->{
            try{startActivity(Scheduler.exactSettings(this));}
            catch(Exception e){toast("無法開啟系統設定");}
        });

        versionText=valueText(13);
        root.addView(compactCard("版本 / 網路",versionText));
        return root;
    }

    private void refreshAll(){
        refreshStatus();
        refreshSchedule();
        refreshSettings();
    }

    private void refreshStatus(){
        CodexClient client=new CodexClient(this);
        Scheduler.Snapshot q=Scheduler.snapshot(this);
        accountLine.setText(client.signedIn()
                ?"ChatGPT 已登入 · "+q.plan+(q.lastCheckMs>0?" · 更新 "+Scheduler.formatTime(q.lastCheckMs):"")
                :"ChatGPT 尚未登入");

        long now=System.currentTimeMillis();
        if(q.primaryActive && q.primaryConfirmed){
            primaryPercent.setText(String.format(Locale.TAIWAN,"%.0f%%",q.primaryUsed));
            double pace=pace(now,q.primaryResetMs,5*60*60_000L);
            primaryBar.setProgress(q.primaryUsed,pace);
            primaryMeta.setText(String.format(Locale.TAIWAN,
                    "已確認啟動 · 時間進度 %.0f%% · reset %s",
                    pace,Scheduler.formatTime(q.primaryResetMs)));
        }else if(q.primaryActive){
            primaryPercent.setText(String.format(Locale.TAIWAN,"%.0f%%",q.primaryUsed));
            primaryBar.setProgress(q.primaryUsed,-1);
            primaryMeta.setText("尚未確認啟動 · 查詢顯示約 "+Scheduler.formatTime(q.primaryResetMs)+"（會滑動）");
        }else{
            primaryPercent.setText("—");
            primaryBar.setProgress(0,-1);
            primaryMeta.setText("目前沒有 5 小時視窗");
        }

        if(q.secondaryActive){
            weeklyPercent.setText(String.format(Locale.TAIWAN,"%.0f%%",q.secondaryUsed));
            double pace=pace(now,q.secondaryResetMs,7*24*60*60_000L);
            weeklyBar.setProgress(q.secondaryUsed,pace);
            weeklyMeta.setText(String.format(Locale.TAIWAN,
                    "時間進度 %.0f%%  ·  reset %s",pace,Scheduler.formatTime(q.secondaryResetMs)));
        }else{
            weeklyPercent.setText("—");
            weeklyBar.setProgress(0,-1);
            weeklyMeta.setText("目前沒有週配額資料");
        }

        String mode=Scheduler.mode(this);
        String modeName=Scheduler.MODE_AUTO.equals(mode)?"Reset 後自動":
                Scheduler.MODE_CUSTOM.equals(mode)?"錨點＋接續":"已關閉";
        scheduleSummary.setText(modeName+"\n"+Scheduler.formattedNext(this));
        lastEvent.setText(Scheduler.last(this));
    }

    private void refreshSchedule(){
        String mode=Scheduler.mode(this);
        modeSpinner.setSelection(Scheduler.MODE_AUTO.equals(mode)?1:Scheduler.MODE_CUSTOM.equals(mode)?2:0);
        nextAlarmText.setText(Scheduler.formattedNext(this)+
                "\n上次系統 Alarm 收到："+Scheduler.lastAlarmReceived(this));
        previewText.setText(Scheduler.MODE_CUSTOM.equals(mode)?Scheduler.customPreview(this):
                Scheduler.MODE_AUTO.equals(mode)?"下一次以目前 quota reset 為基準":"排程已關閉");

        List<ScheduleConfig.Rule> weeklyRules=ScheduleConfig.loadWeekly(this);
        weeklySummary.setText(rulesSummary(weeklyRules,true));
        weeklySummary.setTextColor(ScheduleConfig.conflictingRuleIds(weeklyRules,true).isEmpty()
                ?Color.rgb(35,38,44):Color.rgb(198,104,0));

        ScheduleConfig.DayOverride today=ScheduleConfig.loadOverride(this,LocalDate.now());
        todaySummary.setText(overrideSummary(today,"使用每週預設"));
        ScheduleConfig.DayOverride tomorrow=ScheduleConfig.loadOverride(this,LocalDate.now().plusDays(1));
        tomorrowSummary.setText(overrideSummary(tomorrow,"使用每週預設"));
    }

    private void refreshSettings(){
        boolean signed=new CodexClient(this).signedIn();
        settingsAccount.setText(signed?"已登入":"尚未登入");
        exactAlarmText.setText(Scheduler.canExact(this)?"已允許；Doze 下可使用 exact alarm":"尚未允許，排程可能延後");
        if(ntfyEdit!=null&&!ntfyEdit.hasFocus()){
            ntfyEdit.setText(Scheduler.prefs(this).getString(Scheduler.KEY_NTFY,DEFAULT_NTFY));
        }
        try{
            PackageInfo p=getPackageManager().getPackageInfo(getPackageName(),0);
            versionText.setText(p.versionName+"  ·  build "+p.getLongVersionCode()+
                    "\nAndroid DNS 失敗時自動使用 DoH fallback；Tailscale 開／關皆可。");
        }catch(Exception e){versionText.setText("—");}
    }

    private void applyMode(){
        String mode=modeSpinner.getSelectedItemPosition()==1?Scheduler.MODE_AUTO:
                modeSpinner.getSelectedItemPosition()==2?Scheduler.MODE_CUSTOM:Scheduler.MODE_OFF;
        Scheduler.setMode(this,mode);
        Scheduler.note(this,"排程模式已更新");
        if(!Scheduler.MODE_OFF.equals(mode)&&!Scheduler.canExact(this)){
            try{startActivity(Scheduler.exactSettings(this));}catch(Exception ignored){}
        }
        refreshAll();
        toast("已套用");
    }

    private void editWeekly(){
        List<ScheduleConfig.Rule> draft=copyRules(ScheduleConfig.loadWeekly(this));
        showRuleEditor("每週預設",draft,true,null,()->{
            ScheduleConfig.saveWeekly(this,draft);
            if(Scheduler.MODE_CUSTOM.equals(Scheduler.mode(this)))Scheduler.reschedule(this);
            refreshAll();
        });
    }

    private void editOverride(LocalDate date,String label){
        ScheduleConfig.DayOverride existing=ScheduleConfig.loadOverride(this,date);
        ScheduleConfig.DayOverride draft=new ScheduleConfig.DayOverride(date);
        draft.enabled=existing.enabled;
        draft.rules.addAll(copyRules(existing.rules));

        LinearLayout container=new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(12),dp(4),dp(12),dp(8));

        CheckBox override=new CheckBox(this);
        override.setText("覆寫每週預設");
        override.setChecked(draft.enabled);
        container.addView(override);

        TextView hint=bodyText("關閉覆寫＝照每週預設；開啟但不加任何時段＝當天完全不觸發。");
        container.addView(hint);

        LinearLayout list=new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        container.addView(list);

        Button add=actionButton("＋ 新增錨點");
        container.addView(add,new LinearLayout.LayoutParams(-1,dp(46)));

        Runnable redraw=()->renderRuleRows(list,draft.rules,false,null);
        redraw.run();
        add.setOnClickListener(v->{
            ScheduleConfig.Rule r=new ScheduleConfig.Rule();
            r.hour=9;
            r.minute=0;
            draft.rules.add(r);
            redraw.run();
        });
        override.setOnCheckedChangeListener((b,checked)->{
            draft.enabled=checked;
            list.setAlpha(checked?1f:0.4f);
            add.setEnabled(checked);
        });
        list.setAlpha(draft.enabled?1f:0.4f);
        add.setEnabled(draft.enabled);

        ScrollView scroll=scroll(container);
        new AlertDialog.Builder(this)
                .setTitle(label+" · "+date)
                .setView(scroll)
                .setNegativeButton("取消",null)
                .setPositiveButton("儲存",(d,w)->{
                    draft.enabled=override.isChecked();
                    ScheduleConfig.saveOverride(this,draft);
                    if(Scheduler.MODE_CUSTOM.equals(Scheduler.mode(this)))Scheduler.reschedule(this);
                    refreshAll();
                })
                .show();
    }

    private void showRuleEditor(String title,List<ScheduleConfig.Rule> draft,boolean weekly,
                                Boolean overrideEnabled,Runnable onSave){
        LinearLayout container=new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(12),dp(4),dp(12),dp(8));
        TextView hint=bodyText("每個錨點可設定「後續自動」次數；後續時間依每次實際 5h reset 決定，不會固定累加秒差。");
        container.addView(hint);
        LinearLayout list=new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        container.addView(list);
        Button add=actionButton("＋ 新增錨點");
        container.addView(add,new LinearLayout.LayoutParams(-1,dp(46)));
        Runnable redraw=()->renderRuleRows(list,draft,weekly,null);
        redraw.run();
        add.setOnClickListener(v->{draft.add(new ScheduleConfig.Rule());redraw.run();});

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll(container))
                .setNegativeButton("取消",null)
                .setPositiveButton("儲存",(d,w)->onSave.run())
                .show();
    }

    private void renderRuleRows(LinearLayout list,List<ScheduleConfig.Rule> rules,boolean weekly,Runnable externalRedraw){
        list.removeAllViews();
        Set<String> conflicts=ScheduleConfig.conflictingRuleIds(rules,weekly);
        if(rules.isEmpty()){
            TextView empty=bodyText("沒有錨點。");
            list.addView(empty);
            return;
        }
        for(ScheduleConfig.Rule rule:rules){
            LinearLayout card=new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10),dp(8),dp(10),dp(8));
            boolean conflict=conflicts.contains(rule.id);
            card.setBackground(roundRect(
                    conflict?Color.rgb(255,247,232):Color.WHITE,
                    dp(12),
                    conflict?Color.rgb(230,149,45):Color.rgb(220,224,230)));

            LinearLayout row=new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);

            Switch enabled=new Switch(this);
            enabled.setText("啟用");
            enabled.setChecked(rule.enabled);
            enabled.setOnCheckedChangeListener((b,v)->rule.enabled=v);
            row.addView(enabled,new LinearLayout.LayoutParams(0,dp(46),1));

            Button time=smallButton(rule.timeText());
            time.setOnClickListener(v->new TimePickerDialog(this,(TimePicker p,int h,int m)->{
                rule.hour=h;rule.minute=m;
                renderRuleRows(list,rules,weekly,externalRedraw);
            },rule.hour,rule.minute,true).show());
            row.addView(time,new LinearLayout.LayoutParams(dp(92),dp(44)));

            Spinner auto=new Spinner(this);
            String[] counts={"不接續","+1","+2","+3","+4","+5","+6"};
            auto.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,counts));
            auto.setSelection(Math.min(rule.autoCount,6));
            auto.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
                @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){rule.autoCount=pos;}
                @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
            });
            row.addView(auto,new LinearLayout.LayoutParams(dp(88),dp(44)));

            Button del=smallButton("×");
            del.setOnClickListener(v->{rules.remove(rule);renderRuleRows(list,rules,weekly,externalRedraw);});
            row.addView(del,new LinearLayout.LayoutParams(dp(46),dp(44)));
            card.addView(row);

            if(weekly){
                LinearLayout days=new LinearLayout(this);
                String[] names={"一","二","三","四","五","六","日"};
                for(int i=0;i<7;i++){
                    final int day=i;
                    ToggleButton t=new ToggleButton(this);
                    t.setTextOn(names[i]);
                    t.setTextOff(names[i]);
                    t.setText(names[i]);
                    t.setTextSize(11);
                    t.setMinWidth(0);
                    t.setPadding(0,0,0,0);
                    t.setChecked(rule.days[i]);
                    t.setOnCheckedChangeListener((b,v)->{
                        rule.days[day]=v;
                        renderRuleRows(list,rules,weekly,externalRedraw);
                    });
                    days.addView(t,new LinearLayout.LayoutParams(0,dp(40),1));
                }
                card.addView(days);
            }

            TextView estimate=bodyText((conflict?"⚠ 5 小時內與其他錨點衝突 · ":"預估：")+
                    ScheduleConfig.estimateRule(rule));
            estimate.setTextSize(12);
            if(conflict)estimate.setTextColor(Color.rgb(198,104,0));
            estimate.setPadding(0,dp(2),0,0);
            card.addView(estimate);

            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
            lp.setMargins(0,dp(4),0,dp(4));
            list.addView(card,lp);
        }
    }

    private String rulesSummary(List<ScheduleConfig.Rule> rules,boolean weekly){
        if(rules.isEmpty())return "沒有錨點";
        Set<String> conflicts=ScheduleConfig.conflictingRuleIds(rules,weekly);
        StringBuilder b=new StringBuilder();
        if(!conflicts.isEmpty())b.append("⚠ 有小於 5 小時的錨點衝突\n");
        int shown=0;
        for(ScheduleConfig.Rule r:rules){
            if(!r.enabled)continue;
            if(shown++>0)b.append("\n");
            b.append(weekly?ScheduleConfig.weeklySummary(r):ScheduleConfig.estimateRule(r));
            if(shown>=5&&rules.size()>5){b.append("\n…");break;}
        }
        return b.length()==0?"沒有啟用的錨點":b.toString();
    }

    private String overrideSummary(ScheduleConfig.DayOverride o,String defaultText){
        if(!o.enabled)return defaultText;
        if(o.rules.isEmpty())return "覆寫：當天不觸發";
        return "覆寫\n"+rulesSummary(o.rules,false);
    }

    private List<ScheduleConfig.Rule> copyRules(List<ScheduleConfig.Rule> src){
        List<ScheduleConfig.Rule> out=new ArrayList<>();
        for(ScheduleConfig.Rule r:src)out.add(r.copy());
        return out;
    }

    private void runCheck(){
        toast("正在更新…");
        new Thread(()->{
            Scheduler.checkNow(getApplicationContext());
            runOnUiThread(this::refreshAll);
        },"codex-check").start();
    }

    private void confirmTrigger(){
        new AlertDialog.Builder(this)
                .setTitle("立即觸發？")
                .setMessage("會送出最小 Codex request 並消耗配額。")
                .setNegativeButton("取消",null)
                .setPositiveButton("觸發",(d,w)->{
                    toast("正在觸發…");
                    new Thread(()->{
                        Scheduler.manualTrigger(getApplicationContext());
                        runOnUiThread(this::refreshAll);
                    },"codex-trigger").start();
                }).show();
    }

    private void startLogin(){
        Scheduler.note(this,"等待瀏覽器登入…");
        refreshAll();
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
                    String line;while((line=br.readLine())!=null&&!line.isEmpty()){}
                    URI uri=new URI("http://127.0.0.1"+path);
                    Map<String,String> q=parseQuery(uri.getRawQuery());
                    if(q.get("error")!=null)throw new IllegalStateException("OAuth error: "+q.get("error"));
                    String state=q.get("state");
                    code=q.get("code");
                    if(code==null||!(p.state.equals(state)||(p.state+".onboarding_entrypoint=life_sciences").equals(state)))
                        throw new IllegalStateException("OAuth state/code mismatch");
                    writeHtml(socket,"<h2>Authorization received</h2><p>可以回到 Android Codex Tools。</p>");
                }

                Scheduler.note(this,"已收到授權，正在取得 token…");
                runOnUiThread(this::refreshAll);
                new CodexClient(this).exchangeCode(code,p.verifier,redirect);
                Scheduler.note(this,"登入成功");
                Scheduler.checkNow(getApplicationContext());
            }catch(Exception e){
                Scheduler.note(this,"登入失敗: "+e.getClass().getSimpleName()+": "+e.getMessage());
            }
            runOnUiThread(this::refreshAll);
        },"codex-oauth").start();
    }

    private double pace(long now,long reset,long duration){
        long start=reset-duration;
        if(reset<=0||duration<=0)return -1;
        return Math.max(0,Math.min(100,(now-start)*100.0/duration));
    }

    private View quotaCard(String title,TextView percent,QuotaProgressView bar,TextView meta){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12),dp(8),dp(12),dp(8));
        box.setBackground(roundRect(Color.WHITE,dp(14),Color.rgb(224,227,232)));

        LinearLayout top=new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView label=sectionLabel(title);
        top.addView(label,new LinearLayout.LayoutParams(0,dp(28),1));
        percent.setTextSize(22);
        percent.setTypeface(Typeface.DEFAULT_BOLD);
        percent.setTextColor(Color.rgb(35,38,44));
        top.addView(percent,new LinearLayout.LayoutParams(-2,dp(30)));
        box.addView(top);

        box.addView(bar,new LinearLayout.LayoutParams(-1,dp(13)));

        meta.setTextSize(12);
        meta.setTextColor(Color.rgb(95,100,110));
        meta.setPadding(0,dp(4),0,0);
        box.addView(meta,new LinearLayout.LayoutParams(-1,dp(34)));

        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(88));
        lp.setMargins(0,dp(3),0,dp(3));
        box.setLayoutParams(lp);
        return box;
    }

    private View compactCard(String title,TextView value){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(11),dp(6),dp(11),dp(6));
        box.setBackground(roundRect(Color.WHITE,dp(13),Color.rgb(224,227,232)));
        box.addView(sectionLabel(title));
        box.addView(value);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,dp(3),0,dp(3));
        box.setLayoutParams(lp);
        return box;
    }

    private View summaryEditorCard(String title,TextView summary,Button edit){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(11),dp(8),dp(11),dp(8));
        box.setBackground(roundRect(Color.WHITE,dp(13),Color.rgb(224,227,232)));

        LinearLayout top=new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(sectionLabel(title),new LinearLayout.LayoutParams(0,dp(30),1));
        edit.setText("編輯");
        top.addView(edit,new LinearLayout.LayoutParams(dp(72),dp(40)));
        box.addView(top);
        summary.setPadding(0,dp(2),0,0);
        box.addView(summary);

        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(0,dp(4),0,dp(4));
        box.setLayoutParams(lp);
        return box;
    }

    private TextView sectionLabel(String text){
        TextView t=new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.rgb(85,90,100));
        return t;
    }

    private TextView sectionTitle(String text){
        TextView t=new TextView(this);
        t.setText(text);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.rgb(40,44,50));
        t.setPadding(0,dp(6),0,dp(3));
        return t;
    }

    private TextView valueText(int size){
        TextView t=new TextView(this);
        t.setTextSize(size);
        t.setTextColor(Color.rgb(35,38,44));
        t.setLineSpacing(0,1.05f);
        return t;
    }

    private TextView bodyText(String text){
        TextView t=valueText(13);
        t.setText(text);
        t.setTextColor(Color.rgb(85,90,100));
        t.setPadding(0,dp(4),0,dp(6));
        return t;
    }

    private Button tabButton(String text){
        Button b=smallButton(text);
        b.setTextSize(14);
        return b;
    }

    private Button smallButton(String text){
        Button b=new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setPadding(dp(6),0,dp(6),0);
        return b;
    }

    private Button actionButton(String text){
        Button b=smallButton(text);
        b.setTextColor(Color.rgb(35,38,44));
        return b;
    }

    private void showPage(int i){
        statusPage.setVisibility(i==0?View.VISIBLE:View.GONE);
        schedulePage.setVisibility(i==1?View.VISIBLE:View.GONE);
        settingsPage.setVisibility(i==2?View.VISIBLE:View.GONE);
    }

    private ScrollView scroll(View child){
        ScrollView s=new ScrollView(this);
        s.setFillViewport(true);
        s.addView(child,new ScrollView.LayoutParams(-1,-2));
        return s;
    }

    private LinearLayout.LayoutParams weight(int h){return new LinearLayout.LayoutParams(0,h,1);}
    private LinearLayout.LayoutParams half(boolean left){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);
        if(left)p.setMargins(dp(3),0,0,0); else p.setMargins(0,0,dp(3),0);
        return p;
    }

    private GradientDrawable roundRect(int fill,float radius,int stroke){
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        d.setStroke(dp(1),stroke);
        return d;
    }

    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}

    private static void writeHtml(Socket socket,String bodyHtml) throws Exception{
        String html="<html><head><meta name='viewport' content='width=device-width, initial-scale=1'>"+
                "<style>body{font-family:sans-serif;padding:24px;line-height:1.5}</style></head><body>"+
                bodyHtml+"</body></html>";
        byte[] body=html.getBytes(StandardCharsets.UTF_8);
        OutputStream os=socket.getOutputStream();
        os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+
                body.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        os.write(body);os.flush();
    }

    private static Map<String,String> parseQuery(String raw) throws Exception{
        Map<String,String> map=new HashMap<>();
        if(raw==null)return map;
        for(String pair:raw.split("&")){
            String[] kv=pair.split("=",2);
            map.put(URLDecoder.decode(kv[0],"UTF-8"),kv.length>1?URLDecoder.decode(kv[1],"UTF-8"):"");
        }
        return map;
    }
}
