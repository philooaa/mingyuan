package com.ruoyi.system.finance;

import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Sort and paginate real SQL in a disposable database; never use the business database. */
class TuitionBillOrderingMysqlTest {
    @Test void workflowOrderPrecedesPaginationAndKeepsTimeAndOwnerFilters() throws Exception {
        var fixture=new TuitionMysqlTest();
        try {
            fixture.setup();
            var service=(TuitionService)fixture.service("TuitionService");
            long closed=bill(fixture,"CLOSED","未支付","2026-12-05 10:00:00");
            long paidOld=bill(fixture,"OPEN","已支付","2026-11-20 10:00:00");
            long paidNew=bill(fixture,"OPEN","已支付","2026-12-31 10:00:00");
            long priceOld=bill(fixture,"PENDING_PRICE","未支付","2026-01-01 10:00:00");
            long priceNew=bill(fixture,"PENDING_PRICE","未支付","2026-11-24 10:00:00");
            long priceUnknown=bill(fixture,"PENDING_PRICE","未支付",null);
            long collectOld=bill(fixture,"OPEN","未支付","2026-11-01 10:00:00");
            long collectNew=bill(fixture,"OPEN","未支付","2026-11-25 10:00:00");
            long pending=bill(fixture,"OPEN","未支付","2026-12-25 10:00:00");
            payment(fixture,pending,"PENDING");
            long history=bill(fixture,"HISTORY_PENDING","未支付","2026-12-30 10:00:00");
            long free=bill(fixture,"OPEN","已减免","2026-11-26 10:00:00");
            long legacy=bill(fixture,"OPEN","1","2026-12-01 10:00:00");
            long refunded=bill(fixture,"OPEN","已退款","2026-12-03 10:00:00");
            long successful=bill(fixture,"OPEN","未支付","2026-12-04 10:00:00");
            payment(fixture,successful,"SUCCESS");
            long tiedNew=bill(fixture,"PENDING_PRICE","未支付","2026-11-24 10:00:00");
            List<Long> expected=List.of(tiedNew,priceNew,priceOld,priceUnknown,collectNew,collectOld,pending,history,paidNew,closed,successful,refunded,legacy,free,paidOld);
            var query=new HashMap<>(Map.of("sortBy","WORKFLOW","studentName","排序-","pageSize","3"));
            var actual=new ArrayList<Long>();
            for(int page=1;actual.size()<expected.size();page++) {
                assertTrue(page<10);query.put("pageNum",String.valueOf(page));
                var result=service.listBills(5,true,query);assertEquals((long)expected.size(),result.get("total"));
                actual.addAll(ids(result));
            }
            assertEquals(expected,actual);
            var defaultQuery=Map.of("studentName","排序-","pageSize","100");
            var byId=new ArrayList<>(expected);byId.sort(Comparator.reverseOrder());
            assertEquals(byId,ids(service.listBills(5,true,defaultQuery)));
            assertEquals(byId,ids(service.listBills(2,false,Map.of("studentName","排序-","sortBy","WORKFLOW","pageSize","100"))));
            query.put("billingStatus","PENDING_PRICE");query.put("pageSize","100");query.put("pageNum","1");
            assertEquals(List.of(tiedNew,priceNew,priceOld,priceUnknown),ids(service.listBills(5,true,query)));
            query.remove("billingStatus");query.put("paymentStage","WAIT_ADMIN");
            assertEquals(List.of(pending),ids(service.listBills(1,true,query)));
            assertTrue(ids(service.listBills(3,false,Map.of("parentId","2","sortBy","WORKFLOW","studentName","排序-"))).isEmpty());
        } finally {fixture.cleanup();}
    }
    private long bill(TuitionMysqlTest fixture,String status,String pay,String time) {
        long id=fixture.fresh();
        fixture.db.jdbc().update("UPDATE edu_enrollment SET student_name=CONCAT('排序-',enrollment_id),billing_status=?,pay_status=?,create_time=?,finance_mode='REAL',original_amount=IF(?='HISTORY_PENDING',NULL,220),discount_amount=0,payable_amount=IF(?='HISTORY_PENDING',NULL,220),price_confirmed_by=5,price_confirmed_time=NOW() WHERE enrollment_id=?",status,pay,time,status,status,id);
        return id;
    }
    private void payment(TuitionMysqlTest fixture,long enrollment,String status) {
        fixture.db.jdbc().update("INSERT INTO edu_tuition_payment(payment_no,enrollment_id,channel,payment_status,finance_mode,amount,payer_name,bill_snapshot,recorded_by,confirmed_by,confirmed_time,paid_time,idempotency_key,request_hash) VALUES(CONCAT('ORDER-',?),?,'CASH',?,'REAL',220,'排序测试','{}',5,IF(?='SUCCESS',1,NULL),IF(?='SUCCESS',NOW(),NULL),NOW(),CONCAT('ORDER-',?),REPEAT('a',64))",enrollment,enrollment,status,status,status,enrollment);
    }
    @SuppressWarnings("unchecked")
    private List<Long> ids(Map<String,Object> result) {
        return ((List<Map<String,Object>>)result.get("rows")).stream().map(row->FinanceRules.id(row.get("enrollmentId"))).toList();
    }
}
