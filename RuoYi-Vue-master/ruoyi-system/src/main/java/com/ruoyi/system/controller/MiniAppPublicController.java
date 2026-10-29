package com.ruoyi.system.controller;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.ruoyi.common.annotation.Anonymous;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.shop.ShopRepository;
import com.ruoyi.system.shop.ShopService;

/** Read-only catalogue. Personal records and paid downloads remain authenticated. */
@Anonymous
@RestController
@RequestMapping("/miniapp/public")
public class MiniAppPublicController {
    private final ShopRepository db;
    private final ShopService shop;
    public MiniAppPublicController(ShopRepository db, ShopService shop) { this.db=db; this.shop=shop; }

    @GetMapping("/courses")
    public AjaxResult courses() {
        return AjaxResult.success(db.rows("SELECT schedule_id,course_class_name,grade_name,subject_name,"
            + "campus_name,term_name,period_name,time_slot,start_date,end_date,start_time,end_time,"
            + "class_pattern,lesson_count,tuition_price FROM edu_course_schedule "
            + "WHERE del_flag='0' AND status='0' AND recruit_status='0' "
            + "ORDER BY grade_name,subject_name,schedule_id LIMIT 500"));
    }
    @GetMapping("/material/list")
    public AjaxResult materials(@RequestParam Map<String,String> query) {
        return AjaxResult.success(shop.products(query,0,false,false));
    }
    @GetMapping("/material/{id}")
    public AjaxResult material(@PathVariable long id) {
        return AjaxResult.success(shop.product(id,0,false));
    }
}
