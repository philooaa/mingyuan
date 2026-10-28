package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.*;
import com.ruoyi.common.core.domain.model.*;
import com.ruoyi.common.exception.ServiceException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 仅显式运行在随机 MySQL 副本；真实锁、历史、退回、更正、并发及回滚。 */
public class AttendanceReviewMysqlTest extends ClassLeaveMysqlTest {
    MiniAppSignSubmitBody submitBody(String date) {
        var b=new MiniAppSignSubmitBody();b.setScheduleId(course);b.setClassDate(date);b.setReviewVersion(0L);
        var a=new MiniAppSignSubmitBody.Detail();a.setEnrollmentId(enrollment);a.setStudentName("同名孩子");a.setSourceType("1");a.setSignStatus("3");
        var c=new MiniAppSignSubmitBody.Detail();c.setEnrollmentId(sibling);c.setStudentName("同名孩子");c.setSourceType("1");c.setSignStatus("1");b.setDetails(List.of(a,c));return b;
    }
    MiniAppSignReviewBody reviewBody(String day,String action,long version) {
        var b=new MiniAppSignReviewBody();b.setScheduleId(course);b.setClassDate(day);b.setAction(action);b.setReviewVersion(version);b.setReason("请核对学员到课情况");return b;
    }
    void runReview() throws Exception {
        fixture();String day=date.toString();var sign=submitBody(day);principal(teacher,"teacher");tx(()->signs.submitSignIn(sign,"teacher"));
        var original=db.rows("SELECT sign_in_id,enrollment_id FROM edu_class_sign_in WHERE schedule_id=? AND class_date=? ORDER BY sign_in_id",course,day);
        assertEquals(1L,((Number)((Map<?,?>)signs.selectDetail(course,day).get("review")).get("reviewVersion")).longValue());
        var review=reviewBody(day,"RETURN",1);assertThrows(ServiceException.class,()->tx(()->signs.review(review,teacher,"越权教师")));
        principal(1,"admin");var returned=tx(()->signs.review(review,1L,"管理员"));assertEquals("2",returned.get("reviewStatus"));assertEquals(true,signs.selectDetail(course,day).get("locked"));
        db.jdbc().update("UPDATE edu_course_schedule SET status='1' WHERE schedule_id=?",course);
        assertTrue(signs.selectReviewList(day).stream().allMatch(r->"2".equals(r.getReviewStatus())));
        assertTrue(signs.selectTeacherSchedules(teacher).stream().filter(c->c.getScheduleId()==course).anyMatch(c->c.getCorrections().stream().anyMatch(r->day.equals(r.get("classDate")))));
        assertTrue(signs.selectTeacherSchedules(otherTeacher).stream().allMatch(c->c.getCorrections().isEmpty()));
        principal(teacher,"teacher");assertThrows(ServiceException.class,()->tx(()->signs.submitSignIn(submitBody(date.plusDays(1).toString()),"teacher")));
        principal(otherTeacher,"teacher");sign.setReviewVersion(2L);assertThrows(ServiceException.class,()->tx(()->signs.submitSignIn(sign,"other")));
        principal(teacher,"teacher");sign.setReviewVersion(1L);assertThrows(ServiceException.class,()->tx(()->signs.submitSignIn(sign,"teacher")));sign.setReviewVersion(2L);assertThrows(ServiceException.class,()->tx(()->signs.submitSignIn(sign,"teacher")));
        principal(1,"education");var correction=reviewBody(day,"APPROVE",2);correction.setCorrections(List.of(new MiniAppSignReviewBody.Correction(id(original.get(0),"signInId"),"1","教务核实已到课")));tx(()->signs.correct(correction,1L,"教务"));
        assertEquals(original,db.rows("SELECT sign_in_id,enrollment_id FROM edu_class_sign_in WHERE schedule_id=? AND class_date=? ORDER BY sign_in_id",course,day));
        principal(1,"admin");assertThrows(ServiceException.class,()->tx(()->signs.review(reviewBody(day,"APPROVE",1),1L,"管理员")));
        var approval=reviewBody(day,"APPROVE",3);approval.setImages(List.of("/profile/attendance-proof.jpg"));
        var approved=tx(()->signs.review(approval,1L,"管理员"));assertEquals("1",approved.get("reviewStatus"));assertEquals(1,approved.get("confirmedCount"));assertTrue(approved.get("history").toString().contains("/profile/attendance-proof.jpg"));
        assertEquals("1",tx(()->signs.review(reviewBody(day,"APPROVE",3),1L,"管理员")).get("reviewStatus"));
        assertEquals(4L,db.jdbc().queryForObject("SELECT JSON_LENGTH(review_history) FROM edu_class_sign_in WHERE schedule_id=? AND class_date=? LIMIT 1",Long.class,course,day));
        assertTrue(com.alibaba.fastjson2.JSON.parseArray(db.one("SELECT review_history FROM edu_class_sign_in WHERE schedule_id=? AND class_date=? LIMIT 1",course,day).get("reviewHistory").toString()).toJSONString().contains("\"signStatus\":\"3\""));
        assertThrows(ServiceException.class,()->tx(()->signs.review(reviewBody(day,"RETURN",4),1L,"管理员")));
        principal(teacher,"teacher");sign.setReviewVersion(4L);assertThrows(ServiceException.class,()->tx(()->signs.submitSignIn(sign,"teacher")));
        var personal=new ParentAccountService();inject(personal,"db",db);principal(parent,"parent");
        long account=id(db.one("SELECT lesson_account_id FROM edu_enrollment WHERE enrollment_id=?",enrollment),"lessonAccountId");
        assertEquals(1,personal.childAttendance(account,enrollment).size());assertThrows(ServiceException.class,()->personal.childAttendance(account,sibling));
        principal(otherParent,"parent");assertThrows(ServiceException.class,()->personal.ownedChild(account));
        db.jdbc().update("UPDATE edu_course_schedule SET status='0' WHERE schedule_id=?",course);
        String second=date.plusWeeks(1).toString();var secondSign=submitBody(second);principal(teacher,"teacher");tx(()->signs.submitSignIn(secondSign,"teacher"));
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);var won=new AtomicInteger();
        try{var jobs=new ArrayList<Future<?>>();for(String action:List.of("APPROVE","RETURN"))jobs.add(pool.submit(()->{principal(1,"admin");try{gate.await();tx(()->signs.review(reviewBody(second,action,1),1L,"管理员"));won.incrementAndGet();}catch(ServiceException expected){}catch(Exception e){throw new RuntimeException(e);}}));gate.countDown();for(var job:jobs)job.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertEquals(1,won.get());
        String third=date.plusWeeks(2).toString();var thirdSign=submitBody(third);principal(teacher,"teacher");tx(()->signs.submitSignIn(thirdSign,"teacher"));
        var before=db.rows("SELECT * FROM edu_class_sign_in WHERE schedule_id=? AND class_date=? ORDER BY sign_in_id",course,third);
        db.jdbc().execute("CREATE TRIGGER attendance_review_test_failure BEFORE UPDATE ON edu_class_sign_in FOR EACH ROW BEGIN IF NEW.review_version<>OLD.review_version THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test audit rollback'; END IF; END");
        principal(1,"admin");try{assertThrows(RuntimeException.class,()->tx(()->signs.review(reviewBody(third,"RETURN",1),1L,"管理员")));}finally{db.jdbc().execute("DROP TRIGGER attendance_review_test_failure");}
        assertEquals(before,db.rows("SELECT * FROM edu_class_sign_in WHERE schedule_id=? AND class_date=? ORDER BY sign_in_id",course,third));
        System.out.println("PASS: education confirmation, teacher locked after exception, staff correction, stable attendance IDs, same-name child isolation, role enforcement, optimistic concurrency, duplicate approve, locked history and transaction rollback");
    }
    public static void main(String[] args)throws Exception{var t=new AttendanceReviewMysqlTest();t.setup();t.runReview();}
}
