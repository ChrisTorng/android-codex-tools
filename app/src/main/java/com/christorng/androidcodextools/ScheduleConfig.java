package com.christorng.androidcodextools;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class ScheduleConfig {
    static final String KEY_WEEKLY="schedule_weekly_v2";
    static final String KEY_DAY_OVERRIDES="schedule_day_overrides_v2";

    static final class Rule {
        String id=UUID.randomUUID().toString();
        boolean enabled=true;
        final boolean[] days={true,true,true,true,true,true,true};
        int hour=9;
        int minute=0;
        int autoCount=0;

        Rule copy(){
            Rule r=new Rule();
            r.id=id;
            r.enabled=enabled;
            System.arraycopy(days,0,r.days,0,7);
            r.hour=hour;
            r.minute=minute;
            r.autoCount=autoCount;
            return r;
        }

        String timeText(){
            return String.format(Locale.TAIWAN,"%02d:%02d",hour,minute);
        }
    }

    static final class DayOverride {
        final LocalDate date;
        boolean enabled=false; // false = use weekly default
        final List<Rule> rules=new ArrayList<>();
        DayOverride(LocalDate d){date=d;}
    }

    static final class Slot {
        final long whenMs;
        final int autoCount;
        final String source;
        Slot(long whenMs,int autoCount,String source){
            this.whenMs=whenMs;
            this.autoCount=autoCount;
            this.source=source;
        }
    }

    static List<Rule> loadWeekly(Context c){
        String raw=Scheduler.prefs(c).getString(KEY_WEEKLY,null);
        if(raw==null){
            // Migrate old custom rules if they existed.
            raw=Scheduler.prefs(c).getString("schedule_rules_v1","[]");
        }
        return parseRules(raw,true);
    }

    static void saveWeekly(Context c,List<Rule> rules){
        Scheduler.prefs(c).edit().putString(KEY_WEEKLY,serializeRules(rules,true).toString()).apply();
    }

    static DayOverride loadOverride(Context c,LocalDate date){
        DayOverride out=new DayOverride(date);
        try{
            JSONObject all=new JSONObject(Scheduler.prefs(c).getString(KEY_DAY_OVERRIDES,"{}"));
            JSONObject o=all.optJSONObject(date.toString());
            if(o==null)return out;
            out.enabled=o.optBoolean("enabled",false);
            JSONArray a=o.optJSONArray("rules");
            if(a!=null)out.rules.addAll(parseRules(a.toString(),false));
        }catch(Exception ignored){}
        return out;
    }

    static void saveOverride(Context c,DayOverride override){
        try{
            JSONObject all=new JSONObject(Scheduler.prefs(c).getString(KEY_DAY_OVERRIDES,"{}"));
            JSONObject o=new JSONObject();
            o.put("enabled",override.enabled);
            o.put("rules",serializeRules(override.rules,false));
            all.put(override.date.toString(),o);

            // Keep only today/tomorrow/future-nearby entries; old dates are irrelevant.
            LocalDate today=LocalDate.now();
            List<String> remove=new ArrayList<>();
            java.util.Iterator<String> keys=all.keys();
            while(keys.hasNext()){
                String k=keys.next();
                try{
                    LocalDate d=LocalDate.parse(k);
                    if(d.isBefore(today.minusDays(1))||d.isAfter(today.plusDays(7)))remove.add(k);
                }catch(Exception e){remove.add(k);}
            }
            for(String k:remove)all.remove(k);
            Scheduler.prefs(c).edit().putString(KEY_DAY_OVERRIDES,all.toString()).apply();
        }catch(Exception ignored){}
    }

    static long nextAnchorMs(Context c,long afterMs){
        Slot s=nextAnchor(c,afterMs);
        return s==null?0:s.whenMs;
    }

    static Slot nextAnchor(Context c,long afterMs){
        ZoneId zone=ZoneId.systemDefault();
        ZonedDateTime after=Instant.ofEpochMilli(afterMs).atZone(zone);
        LocalDate start=after.toLocalDate();

        for(int offset=0;offset<=370;offset++){
            LocalDate date=start.plusDays(offset);
            List<Rule> rules=rulesForDate(c,date);
            List<Rule> sorted=new ArrayList<>(rules);
            sorted.sort(Comparator.comparingInt((Rule r)->r.hour).thenComparingInt(r->r.minute));
            for(Rule r:sorted){
                if(!r.enabled)continue;
                ZonedDateTime z=date.atTime(LocalTime.of(r.hour,r.minute)).atZone(zone);
                long when=z.toInstant().toEpochMilli();
                if(when>afterMs){
                    return new Slot(when,r.autoCount,date.equals(LocalDate.now())?"今天":
                            date.equals(LocalDate.now().plusDays(1))?"明天":"每週");
                }
            }
        }
        return null;
    }

    static List<Rule> rulesForDate(Context c,LocalDate date){
        DayOverride ov=loadOverride(c,date);
        if(ov.enabled)return copyList(ov.rules);

        int day=date.getDayOfWeek().getValue()-1;
        List<Rule> out=new ArrayList<>();
        for(Rule r:loadWeekly(c)){
            if(r.enabled&&r.days[day])out.add(r.copy());
        }
        return out;
    }

    static String estimateRule(Rule r){
        StringBuilder b=new StringBuilder(r.timeText());
        LocalTime t=LocalTime.of(r.hour,r.minute);
        for(int i=1;i<=r.autoCount;i++){
            t=t.plusHours(5);
            b.append(" → ").append(String.format(Locale.TAIWAN,"%02d:%02d",t.getHour(),t.getMinute()));
            if(t.getHour()<r.hour && i==1)b.append("(+1日)");
        }
        return b.toString();
    }

    static String weeklySummary(Rule r){
        String[] names={"一","二","三","四","五","六","日"};
        StringBuilder days=new StringBuilder();
        for(int i=0;i<7;i++){
            if(r.days[i]){
                if(days.length()>0)days.append(" ");
                days.append(names[i]);
            }
        }
        return (days.length()==0?"未選":days.toString())+"  "+estimateRule(r);
    }

    private static List<Rule> parseRules(String raw,boolean weekly){
        List<Rule> out=new ArrayList<>();
        try{
            JSONArray a=new JSONArray(raw==null?"[]":raw);
            for(int i=0;i<a.length();i++){
                JSONObject o=a.getJSONObject(i);
                Rule r=new Rule();
                r.id=o.optString("id",UUID.randomUUID().toString());
                r.enabled=o.optBoolean("enabled",true);
                r.hour=o.optInt("hour",9);
                r.minute=o.optInt("minute",0);
                r.autoCount=Math.max(0,Math.min(8,o.optInt("autoCount",0)));
                String ds=o.optString("days",weekly?"1111111":"1111111");
                for(int d=0;d<7;d++)r.days[d]=d<ds.length()&&ds.charAt(d)=='1';
                out.add(r);
            }
        }catch(Exception ignored){}
        return out;
    }

    private static JSONArray serializeRules(List<Rule> rules,boolean weekly){
        JSONArray a=new JSONArray();
        for(Rule r:rules){
            try{
                JSONObject o=new JSONObject();
                o.put("id",r.id);
                o.put("enabled",r.enabled);
                o.put("hour",r.hour);
                o.put("minute",r.minute);
                o.put("autoCount",r.autoCount);
                StringBuilder ds=new StringBuilder();
                for(boolean d:r.days)ds.append(d?'1':'0');
                o.put("days",ds.toString());
                a.put(o);
            }catch(Exception ignored){}
        }
        return a;
    }

    private static List<Rule> copyList(List<Rule> rules){
        List<Rule> out=new ArrayList<>();
        for(Rule r:rules)out.add(r.copy());
        return out;
    }
}
