package com.christorng.androidcodextools;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int ACCENT=Color.rgb(47,128,237);

    private View statusPage,schedulePage,historyPage,settingsPage;
    private Button tabStatus,tabSchedule,tabHistory,tabSettings;
    private UsageHistoryView historyChart;
    private TextView historyList;
    private Spinner themeSpinner,sampleSpinner;
    private boolean darkMode=false;

    private TextView accountLine;
    private TextView primaryPercent,primaryMeta;
    private TextView weeklyPercent,weeklyMeta;
    private RemainingQuotaView primaryBar,weeklyBar;
    private TextView scheduleSummary,lastEvent,manualTriggerPreview,resetCreditsText;
    private View resetCreditsRow;
    private Button triggerButton,resetCreditsButton;

    private Spinner modeSpinner;
    private TextView nextAlarmText,previewText,weeklySummary,todaySummary,tomorrowSummary;

    private TextView settingsAccount,exactAlarmText,versionText;
    private EditText ntfyEdit;
    private Button ntfySaveButton;
    private String ntfySavedValue="";
    private final Handler foregroundHandler=new Handler(Looper.getMainLooper());
    private volatile boolean quotaRefreshRunning=false;
    private long lastAutoRefreshStarted=0;
    private final Runnable periodicQuotaRefresh=new Runnable(){
        @Override public void run(){
            maybeAutoRefresh(false);
            foregroundHandler.postDelayed(this,Scheduler.samplingMinutes(MainActivity.this)*60_000L);
        }
    };

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        darkMode=resolveDarkMode();
        setTheme(darkMode?android.R.style.Theme_Material_NoActionBar:android.R.style.Theme_Material_Light_NoActionBar);
        getWindow().setStatusBarColor(pageBg());
        getWindow().setNavigationBarColor(pageBg());
        getWindow().getDecorView().setSystemUiVisibility(darkMode?0:
                (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        buildUi();
        refreshAll();
    }

    @Override protected void onResume(){
        super.onResume();
        refreshAll();
        maybeAutoRefresh(true);
        foregroundHandler.removeCallbacks(periodicQuotaRefresh);
        foregroundHandler.postDelayed(periodicQuotaRefresh,Scheduler.samplingMinutes(this)*60_000L);
    }

    @Override protected void onPause(){
        foregroundHandler.removeCallbacks(periodicQuotaRefresh);
        super.onPause();
    }

    private void buildUi(){
        LinearLayout shell=new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(pageBg());
        shell.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16),dp(10),dp(10),dp(6));
        TextView title=new TextView(this);
        title.setText("Codex Tools");
        title.setTextSize(23);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(textPrimary());
        header.addView(title,new LinearLayout.LayoutParams(0,dp(44),1));
        Button refreshSmall=smallButton("↻");
        refreshSmall.setContentDescription("更新配額");
        refreshSmall.setOnClickListener(v->runCheck());
        header.addView(refreshSmall,new LinearLayout.LayoutParams(dp(52),dp(42)));
        shell.addView(header);

        LinearLayout tabs=new LinearLayout(this);
        tabs.setPadding(dp(14),0,dp(14),dp(8));
        tabs.setGravity(Gravity.CENTER);
        tabStatus=tabButton("● 狀態");
        tabSchedule=tabButton("◷ 排程");
        tabHistory=tabButton("▥ 歷程");
        tabSettings=tabButton("⚙ 設定");
        tabs.addView(tabStatus,weight(dp(42)));
        tabs.addView(tabSchedule,weight(dp(42)));
        tabs.addView(tabHistory,weight(dp(42)));
        tabs.addView(tabSettings,weight(dp(42)));
        shell.addView(tabs);

        FrameLayout host=new FrameLayout(this);
        statusPage=buildStatusPage();
        schedulePage=buildSchedulePage();
        historyPage=buildHistoryPage();
        settingsPage=buildSettingsPage();
        host.addView(statusPage);
        host.addView(schedulePage);
        host.addView(historyPage);
        host.addView(settingsPage);
        shell.addView(host,new LinearLayout.LayoutParams(-1,0,1));

        tabStatus.setOnClickListener(v->showPage(0));
        tabSchedule.setOnClickListener(v->showPage(1));
        tabHistory.setOnClickListener(v->showPage(2));
        tabSettings.setOnClickListener(v->showPage(3));
        setContentView(shell);
        showPage(0);
    }

    private View buildStatusPage(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14),dp(4),dp(14),dp(8));

        accountLine=new TextView(this);
        accountLine.setTextSize(13);
        accountLine.setTextColor(textSecondary());
        accountLine.setPadding(dp(2),0,0,dp(4));
        root.addView(accountLine);

        primaryPercent=new TextView(this);
        primaryMeta=new TextView(this);
        primaryBar=new RemainingQuotaView(this);
        root.addView(quotaCard("5 小時",primaryPercent,primaryBar,primaryMeta));

        weeklyPercent=new TextView(this);
        weeklyMeta=new TextView(this);
        weeklyBar=new RemainingQuotaView(this);
        root.addView(quotaCard("週配額",weeklyPercent,weeklyBar,weeklyMeta));

        scheduleSummary=valueText(14);
        root.addView(compactCard("下一次排程",scheduleSummary));

        manualTriggerPreview=bodyText("");
        manualTriggerPreview.setTextSize(12);
        manualTriggerPreview.setPadding(dp(4),dp(2),dp(4),dp(3));
        root.addView(manualTriggerPreview,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout actions=new LinearLayout(this);
        Button check=actionButton("更新配額");
        triggerButton=actionButton("立即觸發");
        actions.addView(check,half(false));
        actions.addView(triggerButton,half(true));
        root.addView(actions,new LinearLayout.LayoutParams(-1,dp(48)));
        check.setOnClickListener(v->runCheck());
        triggerButton.setOnClickListener(v->confirmTrigger());

        resetCreditsRow=new LinearLayout(this);
        ((LinearLayout)resetCreditsRow).setOrientation(LinearLayout.HORIZONTAL);
        ((LinearLayout)resetCreditsRow).setGravity(Gravity.CENTER_VERTICAL);
        ((LinearLayout)resetCreditsRow).setPadding(dp(10),dp(4),dp(6),dp(4));
        resetCreditsRow.setBackground(roundRect(
                darkMode?Color.rgb(68,58,34):Color.rgb(255,248,232),
                dp(12),
                darkMode?Color.rgb(174,133,54):Color.rgb(231,183,82)));
        resetCreditsText=valueText(12);
        ((LinearLayout)resetCreditsRow).addView(resetCreditsText,new LinearLayout.LayoutParams(0,dp(40),1));
        resetCreditsButton=smallButton("使用 Reset");
        ((LinearLayout)resetCreditsRow).addView(resetCreditsButton,new LinearLayout.LayoutParams(dp(104),dp(40)));
        resetCreditsButton.setOnClickListener(v->confirmReset());
        resetCreditsRow.setVisibility(View.GONE);
        LinearLayout.LayoutParams resetLp=new LinearLayout.LayoutParams(-1,dp(48));
        resetLp.setMargins(0,dp(3),0,dp(3));
        root.addView(resetCreditsRow,resetLp);

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

    private View buildHistoryPage(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12),dp(4),dp(12),dp(8));

        historyChart=new UsageHistoryView(this);
        root.addView(historyChart,new LinearLayout.LayoutParams(-1,0,1));

        TextView source=bodyText("Global Reset：codex-reset.com · 實線＝實際剩餘 · 虛線＝理想/排程");
        source.setTextSize(10);
        source.setSingleLine(true);
        root.addView(source,new LinearLayout.LayoutParams(-1,dp(22)));

        LinearLayout listCard=new LinearLayout(this);
        listCard.setOrientation(LinearLayout.VERTICAL);
        listCard.setPadding(dp(10),dp(6),dp(10),dp(6));
        listCard.setBackground(roundRect(cardBg(),dp(12),borderColor()));
        listCard.addView(sectionLabel("事件與採樣"));

        ScrollView scroll=new ScrollView(this);
        historyList=valueText(12);
        historyList.setTextIsSelectable(true);
        historyList.setPadding(0,dp(3),0,dp(6));
        scroll.addView(historyList,new ScrollView.LayoutParams(-1,-2));
        listCard.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(listCard,new LinearLayout.LayoutParams(-1,dp(150)));

        return root;
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

        LinearLayout prefRow=new LinearLayout(this);
        prefRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView themeLabel=sectionLabel("主題");
        prefRow.addView(themeLabel,new LinearLayout.LayoutParams(dp(56),dp(46)));
        themeSpinner=new Spinner(this);
        themeSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
                new String[]{"跟隨系統","淺色","深色"}));
        themeSpinner.setSelection(themeSelection());
        prefRow.addView(themeSpinner,new LinearLayout.LayoutParams(0,dp(46),1));

        TextView sampleLabel=sectionLabel("採樣");
        prefRow.addView(sampleLabel,new LinearLayout.LayoutParams(dp(56),dp(46)));
        sampleSpinner=new Spinner(this);
        sampleSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,
                new String[]{"10 分","15 分","30 分","60 分"}));
        sampleSpinner.setSelection(sampleSelection(Scheduler.samplingMinutes(this)));
        prefRow.addView(sampleSpinner,new LinearLayout.LayoutParams(0,dp(46),1));
        root.addView(prefRow,new LinearLayout.LayoutParams(-1,dp(48)));

        themeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            boolean ready=false;
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
                if(!ready){ready=true;return;}
                String mode=pos==1?"light":pos==2?"dark":"system";
                String old=Scheduler.prefs(MainActivity.this).getString("theme_mode","system");
                if(!mode.equals(old)){
                    Scheduler.prefs(MainActivity.this).edit().putString("theme_mode",mode).apply();
                    recreate();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        sampleSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            boolean ready=false;
            @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
                if(!ready){ready=true;return;}
                int[] values={10,15,30,60};
                int selected=values[Math.max(0,Math.min(values.length-1,pos))];
                if(selected!=Scheduler.samplingMinutes(MainActivity.this)){
                    Scheduler.setSamplingMinutes(MainActivity.this,selected);
                    foregroundHandler.removeCallbacks(periodicQuotaRefresh);
                    foregroundHandler.postDelayed(periodicQuotaRefresh,selected*60_000L);
                    Scheduler.note(MainActivity.this,"歷程採樣改為每 "+selected+" 分鐘");
                    refreshAll();
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
        });

        TextView ntfyLabel=sectionTitle("ntfy");
        ntfyLabel.setPadding(0,dp(8),0,0);
        root.addView(ntfyLabel);

        ntfyEdit=new EditText(this);
        ntfyEdit.setSingleLine(true);
        ntfyEdit.setTextSize(14);
        ntfyEdit.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(ntfyEdit,new LinearLayout.LayoutParams(-1,dp(50)));

        LinearLayout ntfyActions=new LinearLayout(this);
        Button testNtfy=smallButton("測試通知");
        Button copyNtfy=smallButton("複製");
        ntfySaveButton=smallButton("儲存");
        ntfyActions.addView(testNtfy,new LinearLayout.LayoutParams(0,dp(42),1));
        ntfyActions.addView(copyNtfy,new LinearLayout.LayoutParams(0,dp(42),1));
        ntfyActions.addView(ntfySaveButton,new LinearLayout.LayoutParams(0,dp(42),1));
        root.addView(ntfyActions,new LinearLayout.LayoutParams(-1,dp(44)));

        TextView ntfyStatus=valueText(12);
        ntfyStatus.setText("最近發送："+Scheduler.ntfyLastStatus(this));
        root.addView(ntfyStatus,new LinearLayout.LayoutParams(-1,dp(34)));

        testNtfy.setOnClickListener(v->{
            toast("正在送測試通知…");
            new Thread(()->{
                Scheduler.testNtfy(getApplicationContext());
                runOnUiThread(()->{
                    ntfyStatus.setText("最近發送："+Scheduler.ntfyLastStatus(this));
                    toast(Scheduler.ntfyLastStatus(this));
                });
            },"ntfy-test").start();
        });

        copyNtfy.setOnClickListener(v->{
            String value=ntfyEdit.getText().toString().trim();
            ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("ntfy URL",value));
            toast("已複製 ntfy URL");
        });
        ntfySaveButton.setOnClickListener(v->{
            ntfySavedValue=ntfyEdit.getText().toString().trim();
            Scheduler.prefs(this).edit().putString(Scheduler.KEY_NTFY,ntfySavedValue).apply();
            ntfySaveButton.setEnabled(false);
            toast("已儲存");
        });
        ntfyEdit.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count){
                if(ntfySaveButton!=null){
                    ntfySaveButton.setEnabled(!s.toString().trim().equals(ntfySavedValue));
                }
            }
            @Override public void afterTextChanged(Editable e){}
        });

        exactAlarmText=valueText(14);
        Button exact=actionButton("精準鬧鐘設定");
        root.addView(summaryEditorCard("精準鬧鐘",exactAlarmText,exact));
        exact.setOnClickListener(v->{
            try{startActivity(Scheduler.exactSettings(this));}
            catch(Exception e){toast("無法開啟系統設定");}
        });

        TextView backgroundHelp=bodyText(
                "Samsung 可能限制休眠 App 的 Alarm / Job。建議把 Codex Tools 加到「永不自動進入休眠」，並確認 App 電池設定不是「受限制」。");
        root.addView(compactCard("背景執行",backgroundHelp));

        LinearLayout batteryRow=new LinearLayout(this);
        Button samsungNeverSleep=smallButton("Samsung 永不休眠");
        Button appBattery=smallButton("App 電池設定");
        batteryRow.addView(samsungNeverSleep,half(false));
        batteryRow.addView(appBattery,half(true));
        root.addView(batteryRow,new LinearLayout.LayoutParams(-1,dp(44)));

        samsungNeverSleep.setOnClickListener(v->openSamsungNeverSleep());
        appBattery.setOnClickListener(v->{
            try{
                Intent i=new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:"+getPackageName()));
                startActivity(i);
            }catch(Exception e){toast("無法開啟 App 設定");}
        });

        versionText=valueText(13);
        root.addView(compactCard("版本 / 網路",versionText));
        return scroll(root);
    }

    private void refreshAll(){
        refreshStatus();
        refreshSchedule();
        refreshHistory();
        refreshSettings();
    }

    private void refreshStatus(){
        CodexClient client=new CodexClient(this);
        Scheduler.Snapshot q=Scheduler.snapshot(this);
        accountLine.setText(client.signedIn()
                ?"ChatGPT 已登入 · "+q.plan+(q.lastCheckMs>0?" · 更新 "+Scheduler.formatTime(q.lastCheckMs):"")
                :"ChatGPT 尚未登入");

        long now=System.currentTimeMillis();
        List<UsageHistory.Sample> history=UsageHistory.loadSamples(this);

        if(q.primaryActive){
            double remaining=Math.max(0,100-q.primaryUsed);
            primaryPercent.setText(String.format(Locale.TAIWAN,"%.0f%% 剩餘",remaining));
            primaryBar.setData(history,true,q.primaryResetMs,5*60*60_000L,
                    remaining,q.primaryConfirmed,darkMode);
        }else{
            primaryPercent.setText("—");
            primaryBar.setData(history,true,0,5*60*60_000L,100,false,darkMode);
        }

        if(q.secondaryActive){
            double remaining=Math.max(0,100-q.secondaryUsed);
            weeklyPercent.setText(String.format(Locale.TAIWAN,"%.0f%% 剩餘",remaining));
            weeklyBar.setData(history,false,q.secondaryResetMs,7*24*60*60_000L,
                    remaining,true,darkMode);
        }else{
            weeklyPercent.setText("—");
            weeklyBar.setData(history,false,0,7*24*60*60_000L,100,false,darkMode);
        }

        String mode=Scheduler.mode(this);
        String modeName=Scheduler.MODE_AUTO.equals(mode)?"Reset 後自動":
                Scheduler.MODE_CUSTOM.equals(mode)?"錨點＋接續":"已關閉";
        scheduleSummary.setText(modeName+" · "+Scheduler.formattedNext(this));

        boolean activeConfirmed=q.primaryConfirmed && q.primaryResetMs>now;
        triggerButton.setEnabled(!activeConfirmed);
        triggerButton.setAlpha(activeConfirmed?0.38f:1f);
        triggerButton.setTextColor(activeConfirmed?disabledText():textPrimary());
        if(activeConfirmed){
            manualTriggerPreview.setText("5h 進行中 · 可再開始：約 "+
                    Scheduler.formatTime(q.primaryResetMs));
        }else{
            manualTriggerPreview.setText("現在觸發 · 預估重置 "+
                    Scheduler.formatTime(now+5*60*60_000L));
        }

        boolean resetEligible=Scheduler.resetEligibleNow(this);
        if(resetEligible && q.resetAvailableCount>0 &&
                q.resetCreditId!=null && !q.resetCreditId.isEmpty()){
            resetCreditsRow.setVisibility(View.VISIBLE);
            String expiry=q.resetExpires==null||q.resetExpires.isEmpty()?"":(" · 到期 "+formatIsoTime(q.resetExpires));
            resetCreditsText.setText("Banked reset "+q.resetAvailableCount+" 次可用"+expiry);
        }else{
            resetCreditsRow.setVisibility(View.GONE);
        }

        if(lastEvent!=null)lastEvent.setText(Scheduler.history(this));
    }

    private void refreshSchedule(){
        String mode=Scheduler.mode(this);
        modeSpinner.setSelection(Scheduler.MODE_AUTO.equals(mode)?1:Scheduler.MODE_CUSTOM.equals(mode)?2:0);
        nextAlarmText.setText(Scheduler.formattedNext(this)+
                "\nAlarm："+Scheduler.lastAlarmReceived(this)+
                "  ·  保底 Job："+Scheduler.lastBackupReceived(this));
        previewText.setText(Scheduler.MODE_CUSTOM.equals(mode)?Scheduler.customPreview(this):
                Scheduler.MODE_AUTO.equals(mode)?"下一次以目前 quota reset 為基準":"排程已關閉");

        List<ScheduleConfig.Rule> weeklyRules=ScheduleConfig.loadWeekly(this);
        weeklySummary.setText(rulesSummary(weeklyRules,true));
        weeklySummary.setTextColor(ScheduleConfig.conflictingRuleIds(weeklyRules,true).isEmpty()
                ?textPrimary():warningText());

        ScheduleConfig.DayOverride today=ScheduleConfig.loadOverride(this,LocalDate.now());
        todaySummary.setText(overrideSummary(today,"使用每週預設"));
        ScheduleConfig.DayOverride tomorrow=ScheduleConfig.loadOverride(this,LocalDate.now().plusDays(1));
        tomorrowSummary.setText(overrideSummary(tomorrow,"使用每週預設"));
    }

    private void refreshHistory(){
        if(historyChart==null)return;
        List<UsageHistory.Sample> samples=UsageHistory.loadSamples(this);
        List<UsageHistory.Event> events=UsageHistory.loadEvents(this);
        historyChart.setData(samples,events,forecastMarkers(),darkMode);

        StringBuilder list=new StringBuilder();
        for(int i=events.size()-1;i>=0;i--){
            UsageHistory.Event e=events.get(i);
            if(list.length()>0)list.append("\n");
            list.append(Scheduler.formatTime(e.at)).append(" · ").append(e.label);
            if(e.source!=null&&!e.source.isEmpty())list.append(" · ").append(e.source);
        }
        if(list.length()==0){
            list.append("尚未偵測到 Reset 事件。");
        }
        list.append("\n\n配額樣本：").append(samples.size()).append(" 筆 · 每 ")
                .append(Scheduler.samplingMinutes(this)).append(" 分鐘目標採樣")
                .append("\n\n詳細紀錄\n").append(Scheduler.history(this));
        if(historyList!=null)historyList.setText(list.toString());
    }

    private List<UsageHistoryView.Marker> forecastMarkers(){
        List<UsageHistoryView.Marker> out=new ArrayList<>();
        ZoneId zone=ZoneId.systemDefault();
        LocalDate today=LocalDate.now(zone);
        for(int day=0;day<=1;day++){
            LocalDate date=today.plusDays(day);
            for(ScheduleConfig.Rule r:ScheduleConfig.rulesForDate(this,date)){
                if(!r.enabled)continue;
                long at=date.atTime(r.hour,r.minute).atZone(zone).toInstant().toEpochMilli();
                if(at>=System.currentTimeMillis()-60_000L)
                    out.add(new UsageHistoryView.Marker(at,"anchor"));
                long autoAt=at;
                for(int i=0;i<r.autoCount;i++){
                    autoAt+=5*60*60_000L;
                    out.add(new UsageHistoryView.Marker(autoAt,"auto"));
                }
            }
        }
        int remaining=Scheduler.chainRemaining(this);
        long chain=Scheduler.chainResetMs(this);
        for(int i=0;i<remaining&&chain>0;i++){
            out.add(new UsageHistoryView.Marker(chain+15_000L+i*5*60*60_000L,"auto"));
        }
        return out;
    }

    private void refreshSettings(){
        boolean signed=new CodexClient(this).signedIn();
        settingsAccount.setText(signed?"已登入":"尚未登入");
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        boolean ignoringDoze=pm!=null && pm.isIgnoringBatteryOptimizations(getPackageName());
        exactAlarmText.setText((Scheduler.canExact(this)?"Exact Alarm 已允許":"Exact Alarm 尚未允許")+
                " · Doze 白名單："+(ignoringDoze?"是":"否"));
        if(ntfyEdit!=null&&!ntfyEdit.hasFocus()){
            ntfySavedValue=Scheduler.ntfyUrl(this);
            ntfyEdit.setText(ntfySavedValue);
            if(ntfySaveButton!=null)ntfySaveButton.setEnabled(false);
        }
        try{
            PackageInfo p=getPackageManager().getPackageInfo(getPackageName(),0);
            versionText.setText(p.versionName+"  ·  build "+p.getLongVersionCode()+
                    "\n系統 DNS 失敗時自動使用 DoH fallback；Tailscale 開／關皆可。");
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

        final Runnable[] redraw={null};
        redraw[0]=()->{
            renderRuleRows(list,draft.rules,false,redraw[0]);
            updateSuggestedAddButton(add,draft.rules,false,draft.enabled);
        };
        redraw[0].run();
        add.setOnClickListener(v->{
            ScheduleConfig.Rule r=ScheduleConfig.suggestedNewRule(draft.rules,false);
            if(r==null){
                toast("當天已沒有可容納完整 5 小時視窗的新錨點");
                updateSuggestedAddButton(add,draft.rules,false,draft.enabled);
                return;
            }
            draft.rules.add(r);
            redraw[0].run();
        });
        override.setOnCheckedChangeListener((b,checked)->{
            draft.enabled=checked;
            list.setAlpha(checked?1f:0.4f);
            updateSuggestedAddButton(add,draft.rules,false,checked);
        });
        list.setAlpha(draft.enabled?1f:0.4f);
        updateSuggestedAddButton(add,draft.rules,false,draft.enabled);

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
        final Runnable[] redraw={null};
        redraw[0]=()->{
            renderRuleRows(list,draft,weekly,redraw[0]);
            updateSuggestedAddButton(add,draft,weekly,true);
        };
        redraw[0].run();

        if(weekly){
            Button recommended=actionButton("套用建議：06:00 ＋ 接續 3 次");
            container.addView(recommended,new LinearLayout.LayoutParams(-1,dp(44)));
            recommended.setOnClickListener(v->{
                draft.clear();
                ScheduleConfig.Rule r=new ScheduleConfig.Rule();
                r.hour=6;
                r.minute=0;
                r.autoCount=3;
                for(int i=0;i<7;i++)r.days[i]=true;
                draft.add(r);
                redraw[0].run();
            });
        }

        add.setOnClickListener(v->{
            ScheduleConfig.Rule r=ScheduleConfig.suggestedNewRule(draft,weekly);
            if(r==null){
                toast("目前排程已沒有可容納完整 5 小時視窗的新錨點");
                updateSuggestedAddButton(add,draft,weekly,true);
                return;
            }
            draft.add(r);
            redraw[0].run();
        });

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
                    conflict?(darkMode?Color.rgb(68,54,32):Color.rgb(255,247,232)):cardBg(),
                    dp(12),
                    conflict?Color.rgb(230,149,45):borderColor()));

            // Row 1: enabled | time | delete
            LinearLayout top=new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);

            Switch enabled=new Switch(this);
            enabled.setText("啟用");
            enabled.setTextSize(13);
            enabled.setChecked(rule.enabled);
            enabled.setOnCheckedChangeListener((b,v)->{
                rule.enabled=v;
                if(externalRedraw!=null)externalRedraw.run();
            });
            top.addView(enabled,new LinearLayout.LayoutParams(0,dp(46),1));

            Button time=smallButton(rule.timeText());
            time.setOnClickListener(v->new TimePickerDialog(this,(TimePicker p,int h,int m)->{
                rule.hour=h;
                rule.minute=m;
                if(externalRedraw!=null)externalRedraw.run();
                else renderRuleRows(list,rules,weekly,null);
            },rule.hour,rule.minute,true).show());
            top.addView(time,new LinearLayout.LayoutParams(dp(96),dp(44)));

            Button del=smallButton("×");
            del.setOnClickListener(v->{
                rules.remove(rule);
                if(externalRedraw!=null)externalRedraw.run();
                else renderRuleRows(list,rules,weekly,null);
            });
            LinearLayout.LayoutParams delLp=new LinearLayout.LayoutParams(dp(46),dp(44));
            delLp.setMargins(dp(6),0,0,0);
            top.addView(del,delLp);
            card.addView(top);

            // Row 2: explicit continuation label + roomy spinner.
            LinearLayout continuation=new LinearLayout(this);
            continuation.setGravity(Gravity.CENTER_VERTICAL);
            TextView autoLabel=bodyText("後續自動");
            autoLabel.setTextSize(12);
            continuation.addView(autoLabel,new LinearLayout.LayoutParams(dp(76),dp(44)));

            Spinner auto=new Spinner(this);
            int maxAuto=ScheduleConfig.maxSafeAutoCount(rule,rules,weekly);
            List<String> countLabels=new ArrayList<>();
            countLabels.add("不接續");
            for(int n=1;n<=maxAuto;n++)countLabels.add("接續 "+n+" 次");
            ArrayAdapter<String> countAdapter=new ArrayAdapter<>(
                    this,android.R.layout.simple_spinner_item,countLabels);
            countAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            auto.setAdapter(countAdapter);
            if(rule.autoCount>maxAuto)rule.autoCount=maxAuto;
            auto.setSelection(Math.min(rule.autoCount,maxAuto));
            auto.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
                boolean initial=true;
                @Override public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){
                    if(rule.autoCount!=pos){
                        rule.autoCount=pos;
                        if(!initial && externalRedraw!=null)externalRedraw.run();
                    }
                    initial=false;
                }
                @Override public void onNothingSelected(android.widget.AdapterView<?> p){}
            });
            continuation.addView(auto,new LinearLayout.LayoutParams(0,dp(44),1));
            card.addView(continuation);

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
                        if(externalRedraw!=null)externalRedraw.run();
                        else renderRuleRows(list,rules,weekly,null);
                    });
                    days.addView(t,new LinearLayout.LayoutParams(0,dp(38),1));
                }
                card.addView(days);
            }

            int safe=ScheduleConfig.maxSafeAutoCount(rule,rules,weekly);
            String estimateText=ScheduleConfig.estimateRule(rule);
            if(conflict){
                estimateText="⚠ 排程衝突 · "+estimateText;
            }else if(rule.autoCount>=safe && safe>0){
                estimateText="預估："+estimateText+" · 已達此錨點安全上限";
            }else{
                estimateText="預估："+estimateText;
            }
            TextView estimate=bodyText(estimateText);
            estimate.setTextSize(12);
            if(conflict)estimate.setTextColor(warningText());
            estimate.setPadding(0,dp(2),0,0);
            card.addView(estimate);

            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
            lp.setMargins(0,dp(4),0,dp(4));
            list.addView(card,lp);
        }
    }

    private void updateSuggestedAddButton(Button add,List<ScheduleConfig.Rule> rules,
                                          boolean weekly,boolean editorEnabled){
        if(!editorEnabled){
            add.setEnabled(false);
            add.setText("＋ 新增錨點");
            return;
        }
        ScheduleConfig.Rule suggested=ScheduleConfig.suggestedNewRule(rules,weekly);
        if(suggested==null){
            add.setEnabled(false);
            add.setText("已無可新增錨點");
        }else{
            add.setEnabled(true);
            add.setText("＋ 新增錨點 · 建議 "+suggested.timeText());
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
        if(quotaRefreshRunning)return;
        quotaRefreshRunning=true;
        lastAutoRefreshStarted=System.currentTimeMillis();
        new Thread(()->{
            try{Scheduler.checkNow(getApplicationContext(),true);}
            finally{
                quotaRefreshRunning=false;
                runOnUiThread(this::refreshAll);
            }
        },"codex-check").start();
    }

    private void maybeAutoRefresh(boolean enteringScreen){
        if(!new CodexClient(this).signedIn()||quotaRefreshRunning)return;
        long now=System.currentTimeMillis();
        long minGap=enteringScreen?15_000L:10*60_000L;
        if(now-lastAutoRefreshStarted<minGap)return;
        quotaRefreshRunning=true;
        lastAutoRefreshStarted=now;
        new Thread(()->{
            try{Scheduler.checkNow(getApplicationContext(),true);}
            finally{
                quotaRefreshRunning=false;
                runOnUiThread(this::refreshAll);
            }
        },"codex-auto-refresh").start();
    }

    private void confirmReset(){
        Scheduler.Snapshot q=Scheduler.snapshot(this);
        if(!Scheduler.resetEligibleNow(this)){
            toast("5 小時與週配額都尚未用盡");
            return;
        }
        if(q.resetAvailableCount<=0||q.resetCreditId==null||q.resetCreditId.isEmpty()){
            toast("目前沒有可用的 banked reset");
            return;
        }
        String title=q.resetTitle==null||q.resetTitle.isEmpty()?"Full reset":q.resetTitle;
        String detail=q.resetDescription==null?"":q.resetDescription;
        String expiry=q.resetExpires==null||q.resetExpires.isEmpty()?"":"\n到期："+formatIsoTime(q.resetExpires);
        new AlertDialog.Builder(this)
                .setTitle("使用 "+title+"？")
                .setMessage("這會消耗 1 個 banked reset。只有 5 小時或週配額已用盡時才會執行；完成後 App 會重新查詢實際 quota，不會自動送 Codex request。"+
                        (detail.isEmpty()?"":"\n\n"+detail)+expiry)
                .setNegativeButton("取消",null)
                .setPositiveButton("使用 Reset",(d,w)->{
                    resetCreditsButton.setEnabled(false);
                    toast("正在 Reset…");
                    new Thread(()->{
                        Scheduler.consumeResetCredit(getApplicationContext());
                        runOnUiThread(()->{
                            resetCreditsButton.setEnabled(true);
                            refreshAll();
                        });
                    },"codex-reset").start();
                }).show();
    }

    private void confirmTrigger(){
        Scheduler.Snapshot q=Scheduler.snapshot(this);
        long now=System.currentTimeMillis();
        if(q.primaryConfirmed && q.primaryResetMs>now){
            toast("5 小時視窗仍在進行中");
            return;
        }
        String timing=(q.primaryConfirmed && q.primaryResetMs>now)
                ?"目前 5 小時視窗已啟動，這次 request 不會延長視窗。\n目前 reset："+Scheduler.formatTime(q.primaryResetMs)
                :"若觸發成功，預估 reset："+Scheduler.formatTime(now+5*60*60_000L);
        new AlertDialog.Builder(this)
                .setTitle("立即觸發？")
                .setMessage("會送出最小 Codex request 並消耗配額。\n\n"+timing)
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
                    writeHtml(socket,"<h2>Authorization received</h2><p>可以回到 Codex Tools。</p>");
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

    private float[] hourTicks(long reset,long duration){
        if(reset<=0||duration<=0)return new float[0];
        long start=reset-duration;
        ZonedDateTime z=Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault());
        ZonedDateTime tick=z.truncatedTo(ChronoUnit.HOURS).plusHours(1);
        List<Float> out=new ArrayList<>();
        while(tick.toInstant().toEpochMilli()<=reset && out.size()<8){
            long ms=tick.toInstant().toEpochMilli();
            float p=(float)((ms-start)*100.0/duration);
            if(p>0f&&p<100f)out.add(p);
            tick=tick.plusHours(1);
        }
        float[] arr=new float[out.size()];
        for(int i=0;i<out.size();i++)arr[i]=out.get(i);
        return arr;
    }

    private float[] midnightTicks(long reset,long duration){
        if(reset<=0||duration<=0)return new float[0];
        long start=reset-duration;
        ZonedDateTime z=Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault());
        ZonedDateTime tick=z.toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault());
        List<Float> out=new ArrayList<>();
        while(tick.toInstant().toEpochMilli()<=reset && out.size()<8){
            long ms=tick.toInstant().toEpochMilli();
            float p=(float)((ms-start)*100.0/duration);
            if(p>0f&&p<100f)out.add(p);
            tick=tick.plusDays(1);
        }
        float[] arr=new float[out.size()];
        for(int i=0;i<out.size();i++)arr[i]=out.get(i);
        return arr;
    }

    private String elapsedLabel(long now,long reset,long duration){
        long start=reset-duration;
        long elapsed=Math.max(0,Math.min(duration,now-start));
        if(duration>=24*60*60_000L){
            long days=elapsed/(24*60*60_000L);
            long hours=(elapsed%(24*60*60_000L))/(60*60_000L);
            return days>0?days+"天 "+hours+"h":hours+"h";
        }
        long hours=elapsed/(60*60_000L);
        long mins=(elapsed%(60*60_000L))/60_000L;
        return hours+"h "+mins+"m";
    }

    private String formatIsoTime(String iso){
        try{
            return Scheduler.formatTime(java.time.Instant.parse(iso).toEpochMilli());
        }catch(Exception e){
            return iso;
        }
    }

    private double pace(long now,long reset,long duration){
        long start=reset-duration;
        if(reset<=0||duration<=0)return -1;
        return Math.max(0,Math.min(100,(now-start)*100.0/duration));
    }

    private View quotaCard(String title,TextView percent,RemainingQuotaView chart,TextView meta){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(10),dp(6),dp(10),dp(5));
        box.setBackground(roundRect(cardBg(),dp(14),borderColor()));

        LinearLayout top=new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView label=sectionLabel(title);
        top.addView(label,new LinearLayout.LayoutParams(0,dp(25),1));
        percent.setTextSize(17);
        percent.setTypeface(Typeface.DEFAULT_BOLD);
        percent.setTextColor(textPrimary());
        top.addView(percent,new LinearLayout.LayoutParams(-2,dp(27)));
        box.addView(top);

        box.addView(chart,new LinearLayout.LayoutParams(-1,dp(96)));

        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(132));
        lp.setMargins(0,dp(3),0,dp(3));
        box.setLayoutParams(lp);
        return box;
    }

    private View compactCard(String title,TextView value){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(11),dp(6),dp(11),dp(6));
        box.setBackground(roundRect(cardBg(),dp(13),borderColor()));
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
        box.setBackground(roundRect(cardBg(),dp(13),borderColor()));

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
        t.setTextColor(textSecondary());
        return t;
    }

    private TextView sectionTitle(String text){
        TextView t=new TextView(this);
        t.setText(text);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(textPrimary());
        t.setPadding(0,dp(6),0,dp(3));
        return t;
    }

    private TextView valueText(int size){
        TextView t=new TextView(this);
        t.setTextSize(size);
        t.setTextColor(textPrimary());
        t.setLineSpacing(0,1.05f);
        return t;
    }

    private TextView bodyText(String text){
        TextView t=valueText(13);
        t.setText(text);
        t.setTextColor(textSecondary());
        t.setPadding(0,dp(4),0,dp(6));
        return t;
    }

    private Button tabButton(String text){
        Button b=smallButton(text);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setElevation(0f);
        return b;
    }

    private void styleTab(Button b,boolean selected){
        b.setTextColor(selected?Color.WHITE:textSecondary());
        b.setBackground(roundRect(
                selected?ACCENT:Color.TRANSPARENT,
                dp(12),
                selected?ACCENT:Color.TRANSPARENT));
        b.setAlpha(selected?1f:0.9f);
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
        b.setTextColor(textPrimary());
        return b;
    }

    private void openSamsungNeverSleep(){
        try{
            Intent i=new Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY");
            i.setPackage("com.samsung.android.lool");
            i.putExtra("activity_type",2);
            startActivity(i);
        }catch(Exception samsung){
            try{
                Intent i=new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                startActivity(i);
            }catch(Exception e){
                toast("無法開啟背景用量設定");
            }
        }
    }

    private void showPage(int i){
        statusPage.setVisibility(i==0?View.VISIBLE:View.GONE);
        schedulePage.setVisibility(i==1?View.VISIBLE:View.GONE);
        historyPage.setVisibility(i==2?View.VISIBLE:View.GONE);
        settingsPage.setVisibility(i==3?View.VISIBLE:View.GONE);
        if(tabStatus!=null){
            styleTab(tabStatus,i==0);
            styleTab(tabSchedule,i==1);
            styleTab(tabHistory,i==2);
            styleTab(tabSettings,i==3);
        }
    }

    private boolean resolveDarkMode(){
        String mode=Scheduler.prefs(this).getString("theme_mode","system");
        if("dark".equals(mode))return true;
        if("light".equals(mode))return false;
        int night=getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night==Configuration.UI_MODE_NIGHT_YES;
    }

    private int themeSelection(){
        String mode=Scheduler.prefs(this).getString("theme_mode","system");
        return "light".equals(mode)?1:"dark".equals(mode)?2:0;
    }

    private int sampleSelection(int minutes){
        if(minutes>=60)return 3;
        if(minutes>=30)return 2;
        if(minutes>=15)return 1;
        return 0;
    }

    private int pageBg(){return darkMode?Color.rgb(18,20,23):Color.rgb(247,248,250);}
    private int cardBg(){return darkMode?Color.rgb(31,34,39):Color.WHITE;}
    private int borderColor(){return darkMode?Color.rgb(61,66,75):Color.rgb(224,227,232);}
    private int textPrimary(){return darkMode?Color.rgb(235,237,241):Color.rgb(35,38,44);}
    private int textSecondary(){return darkMode?Color.rgb(166,172,183):Color.rgb(85,90,100);}
    private int disabledText(){return darkMode?Color.rgb(92,97,106):Color.rgb(145,149,156);}
    private int warningText(){return darkMode?Color.rgb(244,172,76):Color.rgb(198,104,0);}

    private String axisTime(long ms,boolean weekly){
        if(ms<=0)return "";
        ZonedDateTime z=Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault());
        LocalDate d=z.toLocalDate();
        if(weekly){
            return String.format(Locale.TAIWAN,"%d/%d %02d:%02d",
                    d.getMonthValue(),d.getDayOfMonth(),z.getHour(),z.getMinute());
        }
        LocalDate today=LocalDate.now();
        String time=String.format(Locale.TAIWAN,"%02d:%02d",z.getHour(),z.getMinute());
        if(d.equals(today))return time;
        if(d.equals(today.plusDays(1)))return "明 "+time;
        if(d.equals(today.minusDays(1)))return "昨 "+time;
        return String.format(Locale.TAIWAN,"%d/%d %s",d.getMonthValue(),d.getDayOfMonth(),time);
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
