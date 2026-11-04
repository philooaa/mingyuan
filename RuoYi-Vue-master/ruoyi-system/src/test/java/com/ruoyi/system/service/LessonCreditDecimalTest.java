package com.ruoyi.system.service;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.ruoyi.system.shop.ShopRepository;
import static org.junit.jupiter.api.Assertions.*;

/** Decimal accounting and retry behavior; database locking is tested separately. */
class LessonCreditDecimalTest {
    static class MemoryDb extends ShopRepository {
        Map<String,Object> account=new HashMap<>(Map.of("accountId",1L,"parentId",2L,"remainingCredits","3.0","reservedCredits","0.0","consumedCredits","0.0"));
        List<Map<String,Object>> events=new ArrayList<>();
        MemoryDb() { super(new DriverManagerDataSource()); }
        @Override public Map<String,Object> one(String sql,Object... args) { return new HashMap<>(account); }
        @Override public List<Map<String,Object>> rows(String sql,Object... args) {
            if(sql.contains("request_key=?")) return events.stream().filter(e->Objects.equals(e.get("requestKey"),args[0])).toList();
            if(sql.contains("action='HOLD'")) return events.stream().filter(e->Objects.equals(e.get("enrollmentId"),args[0]) && "HOLD".equals(e.get("action"))).toList();
            if(sql.contains("action IN")) return events.stream().filter(e->Objects.equals(e.get("enrollmentId"),args[0]) && List.of("RELEASE","CONSUME").contains(e.get("action"))).toList();
            return List.of();
        }
        @Override public void update(String table,String idColumn,long id,Map<String,Object> values) {
            account.put("remainingCredits",values.get("remaining_credits")); account.put("reservedCredits",values.get("reserved_credits")); account.put("consumedCredits",values.get("consumed_credits"));
        }
        @Override public long insert(String table,Map<String,Object> values) {
            Map<String,Object> e=new HashMap<>(); values.forEach((k,v)->{String[] parts=k.split("_");String key=parts[0];for(int i=1;i<parts.length;i++) key+=parts[i].substring(0,1).toUpperCase()+parts[i].substring(1);e.put(key,v);});events.add(e);return events.size();
        }
    }
    @Test void decimalHoldsConsumeAndReleaseRemainIdempotent() throws Exception {
        ScheduleCrudTest.principal(1,"admin"); MemoryDb db=new MemoryDb(); LessonCreditService service=new LessonCreditService(); ScheduleCrudTest.inject(service,"db",db);
        BigDecimal cost=new BigDecimal("1.5");
        service.hold(new HashMap<>(db.account),11L,cost); service.hold(new HashMap<>(db.account),11L,cost);
        assertEquals(0,new BigDecimal("1.5").compareTo(new BigDecimal(db.account.get("reservedCredits").toString())));
        Map<String,Object> enrollment=Map.of("settlementMethod","P","lessonAccountId",1L,"parentId",2L,"enrollmentId",11L,"lessonCredits","1.5");
        service.consume(enrollment); service.consume(enrollment);
        assertEquals(0,new BigDecimal("1.5").compareTo(new BigDecimal(db.account.get("remainingCredits").toString())));
        assertEquals(0,new BigDecimal("1.5").compareTo(new BigDecimal(db.account.get("consumedCredits").toString())));
        service.hold(new HashMap<>(db.account),12L,cost);
        service.release(Map.of("settlementMethod","P","lessonAccountId",1L,"parentId",2L,"enrollmentId",12L,"lessonCredits","1.5"));
        assertEquals(0,new BigDecimal(db.account.get("reservedCredits").toString()).signum());
        assertEquals(0,new BigDecimal("1.5").compareTo(new BigDecimal(db.account.get("remainingCredits").toString())));
    }
}
