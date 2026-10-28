package com.ruoyi.system.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import com.ruoyi.common.exception.ServiceException;

/** 显式隔离 MySQL：课程矩阵、同名学生、跨课身份、重复课次、历史与分页。 */
public class AttendanceOverviewMysqlTest extends AttendanceReviewMysqlTest {
    long attendance(long schedule,String date,Long enrollment,String name,String source,String status,String review) {
        var row=new LinkedHashMap<String,Object>();row.put("schedule_id",schedule);row.put("class_date",date);row.put("enrollment_id",enrollment);row.put("student_name",name);row.put("source_type",source);row.put("sign_status",status);row.put("review_status",review);return db.insert("edu_class_sign_in",row);
    }
    @SuppressWarnings("unchecked") void runOverview()throws Exception {
        fixture();String first=date.toString(),second=date.plusWeeks(1).toString();
        attendance(course,first,enrollment,"同名孩子","1","1","0");attendance(course,first,sibling,"同名孩子","1","3","1");attendance(course,second,enrollment,"同名孩子","1","2","2");
        attendance(course,first,null,"临时同名","2","4","0");attendance(course,second,null,"临时同名","3","5","1");
        long account=id(db.one("SELECT lesson_account_id FROM edu_enrollment WHERE enrollment_id=?",enrollment),"lessonAccountId"),student=id(db.one("SELECT student_id FROM edu_enrollment WHERE enrollment_id=?",enrollment),"studentId");String key="S:"+student;
        principal(1,"admin");var overview=tx(()->signs.attendanceOverview(course,null,null));var rows=(List<Map<String,Object>>)overview.get("rows");assertEquals(4,rows.size());assertEquals(List.of(first,second),overview.get("dates"));assertEquals(3L,((Map<String,Long>)overview.get("stats")).get("attended"));
        assertEquals(2,rows.stream().filter(r->"同名孩子".equals(r.get("studentName"))).count());assertEquals(2,rows.stream().filter(r->(boolean)r.get("temporary")).count());
        var siblingRow=rows.stream().filter(r->!key.equals(r.get("studentKey"))&&"同名孩子".equals(r.get("studentName"))).findFirst().orElseThrow();assertEquals(0L,((Map<String,Long>)siblingRow.get("stats")).get("attended"));assertEquals("—",((List<Map<String,Object>>)siblingRow.get("cells")).get(1).get("text"));
        var current=new LessonCreditService.AccountDraft();current.studentName="当前新姓名";current.gradeName=grade;current.contactPhone="12345000001";principal(parent,"parent");tx(()->{credits.editChild(account,current,false);return null;});principal(1,"admin");
        assertTrue(((List<Map<String,Object>>)tx(()->signs.attendanceOverview(course,null,null)).get("rows")).stream().anyMatch(r->key.equals(r.get("studentKey"))&&"当前新姓名".equals(r.get("studentName"))));assertEquals("同名孩子",db.one("SELECT student_name FROM edu_class_sign_in WHERE enrollment_id=? LIMIT 1",enrollment).get("studentName"));
        long originalCourse=course;long otherCourse=publish(otherTeacher,otherRoom,date.plusDays(20));course=otherCourse;long again=classEnrollment(account);course=originalCourse;attendance(otherCourse,date.plusDays(20).toString(),again,"同名孩子","1","1","1");principal(1,"admin");
        var all=tx(()->signs.attendanceStudent(course,key,true,null,null,1));assertEquals(3,all.get("total"));assertEquals(2L,((Map<String,Long>)all.get("stats")).get("attended"));assertEquals(2,tx(()->signs.attendanceStudent(course,key,false,null,null,1)).get("total"));
        var historical=new LinkedHashMap<String,Object>();historical.put("enrollment_code",UUID.randomUUID().toString().replace("-",""));historical.put("schedule_id",course);historical.put("parent_id",parent);historical.put("student_id",student);historical.put("lesson_account_id",account);historical.put("student_name","原报名快照");historical.put("enrollment_status","2");long duplicate=db.insert("edu_enrollment",historical);attendance(course,first,duplicate,"原报名快照","1","3","0");
        var collision=tx(()->signs.attendanceOverview(course,null,null));var child=((List<Map<String,Object>>)collision.get("rows")).stream().filter(r->key.equals(r.get("studentKey"))).findFirst().orElseThrow();assertEquals(1L,((Map<String,Long>)child.get("stats")).get("conflicts"));assertEquals(0L,((Map<String,Long>)child.get("stats")).get("attended"));assertEquals("待核对",((List<Map<String,Object>>)child.get("cells")).get(0).get("text"));
        var ownAll=tx(()->signs.attendanceStudent(course,key,true,null,null,1));assertEquals(1L,((Map<String,Long>)ownAll.get("stats")).get("attended"));assertEquals(1L,((Map<String,Long>)ownAll.get("stats")).get("conflicts"));
        var range=tx(()->signs.attendanceOverview(course,second,second));assertEquals(List.of(second),range.get("dates"));assertThrows(ServiceException.class,()->tx(()->signs.attendanceOverview(course,second,first)));assertThrows(ServiceException.class,()->tx(()->signs.attendanceOverview(course,"2028-02-30",null)));
        String temporary=(String)rows.stream().filter(r->(boolean)r.get("temporary")).findFirst().orElseThrow().get("studentKey");assertThrows(ServiceException.class,()->tx(()->signs.attendanceStudent(course,temporary,true,null,null,1)));assertThrows(ServiceException.class,()->tx(()->signs.attendanceStudent(otherCourse,temporary,false,null,null,1)));assertThrows(ServiceException.class,()->tx(()->signs.attendanceStudent(course,"S:999999999",true,null,null,1)));
        for(int i=0;i<31;i++)attendance(course,date.plusDays(100+i).toString(),enrollment,"同名孩子","1","1","1");
        var page=tx(()->signs.attendanceStudent(course,key,false,null,null,1));assertEquals(30,((List<?>)page.get("rows")).size());assertEquals(33,page.get("total"));assertEquals(3,((List<?>)tx(()->signs.attendanceStudent(course,key,false,null,null,2)).get("rows")).size());
        var before=db.rows("SELECT * FROM edu_class_sign_in ORDER BY sign_in_id");tx(()->signs.attendanceOverview(course,null,null));tx(()->signs.attendanceStudent(course,key,true,null,null,1));assertTrue(signs.attendanceCourses("isolated").stream().anyMatch(r->String.valueOf(course).equals(r.get("scheduleId"))));assertEquals(before,db.rows("SELECT * FROM edu_class_sign_in ORDER BY sign_in_id"));
        principal(parent,"parent");assertThrows(ServiceException.class,()->signs.attendanceCourses(""));assertThrows(ServiceException.class,()->tx(()->signs.attendanceOverview(course,null,null)));principal(teacher,"teacher");assertThrows(ServiceException.class,()->tx(()->signs.attendanceStudent(course,key,false,null,null,1)));
        System.out.println("PASS: attendance matrix, all occurrences, current profile versus preserved snapshot, same-name child isolation, temporary identities, cross-course statistics, unrecorded cells, duplicate conflict exclusion, date validation, pagination, role access and read-only data");
    }
    public static void main(String[] args)throws Exception{var t=new AttendanceOverviewMysqlTest();t.setup();t.runOverview();}
}
