package com.christorng.androidcodextools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ScheduleConfig {
    static final String KEY_RULES="schedule_rules_v1";
    static final String KEY_OVERRIDES="schedule_overrides_v1";

    static final class Rule {
        String id;
        boolean enabled;
        final boolean[] days=new boolean[7]; // Monday..Sunday
        int hour;
        int minute;

        Rule(){
            id=UUID.randomUUID().toString();
            enabled=true;
            hour=8;
            minute=0;
            for(int i=0;i<5;i++)days[i]=true;
        }

        Rule copy(){
            Rule r=new Rule();
            r.id=id;
            r.enabled=enabled;
            System.arraycopy(days,0,r.days,0,7);
            r.hour=hour;
            r.minute=minute;
            return r;
        }

        String timeText(){
            return String.format(java.util.Locale.US,"%02d:%02d",hour,minute);
        }
    }

    static List<Rule> loadRules(Context c){
        String raw=Scheduler.prefs(c).getString(KEY_RULES,"[]");
        List<Rule> out=new ArrayList<>();
        try{
            JSONArray a=new JSONArray(raw);
            for(int i=0;i<a.length();i++){
                JSONObject o=a.getJSONObject(i);
                Rule r=new Rule();
                r.id=o.optString("id",UUID.randomUUID().toString());
                r.enabled=o.optBoolean("enabled",true);
                String days=o.optString("days","1111100");
                for(int d=0;d<7;d++)r.days[d]=d<days.length()&&days.charAt(d)=='1';
                r.hour=o.optInt("hour",8);
                r.minute=o.optInt("minute",0);
                out.add(r);
            }
        }catch(Exception ignored){}
        return out;
    }

    static void saveRules(Context c,List<Rule> rules,String overrides) throws Exception{
        validateOverrides(overrides);
        JSONArray a=new JSONArray();
        for(Rule r:rules){
            JSONObject o=new JSONObject();
            o.put("id",r.id);
            o.put("enabled",r.enabled);
            StringBuilder ds=new StringBuilder();
            for(boolean d:r.days)ds.append(d?'1':'0');
            o.put("days",ds.toString());
            o.put("hour",r.hour);
            o.put("minute",r.minute);
            a.put(o);
        }
        Scheduler.prefs(c).edit()
                .putString(KEY_RULES,a.toString())
                .putString(KEY_OVERRIDES,overrides==null?"":overrides.trim())
                .apply();
    }

    static String loadOverrides(Context c){
        return Scheduler.prefs(c).getString(KEY_OVERRIDES,"");
    }

    static long nextCustomSlot(Context c,long afterMs){
        List<Rule> rules=loadRules(c);
        Map<LocalDate,List<LocalTime>> overrides=parseOverrides(loadOverrides(c));
        ZoneId zone=ZoneId.systemDefault();
        ZonedDateTime after=Instant.ofEpochMilli(afterMs).atZone(zone);
        LocalDate start=after.toLocalDate();

        for(int offset=0;offset<=370;offset++){
            LocalDate date=start.plusDays(offset);
            List<LocalTime> times=new ArrayList<>();

            if(overrides.containsKey(date)){
                times.addAll(overrides.get(date));
            }else{
                int idx=date.getDayOfWeek().getValue()-1;
                for(Rule r:rules){
                    if(r.enabled && r.days[idx]){
                        times.add(LocalTime.of(r.hour,r.minute));
                    }
                }
            }

            Collections.sort(times);
            LocalTime previous=null;
            for(LocalTime t:times){
                if(previous!=null && previous.equals(t))continue;
                previous=t;
                ZonedDateTime candidate=date.atTime(t).atZone(zone);
                if(candidate.toInstant().toEpochMilli()>afterMs){
                    return candidate.toInstant().toEpochMilli();
                }
            }
        }
        return 0;
    }

    static void validateOverrides(String raw) throws Exception{
        parseOverridesStrict(raw==null?"":raw);
    }

    private static Map<LocalDate,List<LocalTime>> parseOverrides(String raw){
        try{return parseOverridesStrict(raw==null?"":raw);}
        catch(Exception e){return new LinkedHashMap<>();}
    }

    private static Map<LocalDate,List<LocalTime>> parseOverridesStrict(String raw) throws Exception{
        Map<LocalDate,List<LocalTime>> out=new LinkedHashMap<>();
        String[] lines=raw.split("\\r?\\n");
        DateTimeFormatter tf=DateTimeFormatter.ofPattern("H:mm");
        for(int i=0;i<lines.length;i++){
            String line=lines[i].trim();
            if(line.isEmpty()||line.startsWith("#"))continue;
            String[] kv=line.split("=",2);
            if(kv.length!=2)throw new IllegalArgumentException("特殊日期第 "+(i+1)+" 行格式錯誤");
            LocalDate date;
            try{date=LocalDate.parse(kv[0].trim());}
            catch(DateTimeParseException e){throw new IllegalArgumentException("特殊日期第 "+(i+1)+" 行日期錯誤");}
            String value=kv[1].trim();
            List<LocalTime> times=new ArrayList<>();
            if(!value.equalsIgnoreCase("off")&&!value.equalsIgnoreCase("skip")&&!value.equals("-")){
                if(value.isEmpty())throw new IllegalArgumentException("特殊日期第 "+(i+1)+" 行未指定時間或 off");
                for(String part:value.split(",")){
                    try{times.add(LocalTime.parse(part.trim(),tf));}
                    catch(Exception e){throw new IllegalArgumentException("特殊日期第 "+(i+1)+" 行時間錯誤: "+part.trim());}
                }
                times.sort(Comparator.naturalOrder());
            }
            out.put(date,times);
        }
        return out;
    }

    static String ruleSummary(Rule r){
        String[] names={"一","二","三","四","五","六","日"};
        StringBuilder b=new StringBuilder();
        for(int i=0;i<7;i++){
            if(r.days[i]){
                if(b.length()>0)b.append("、");
                b.append(names[i]);
            }
        }
        return (b.length()==0?"未選日期":b.toString())+" "+r.timeText();
    }
}
