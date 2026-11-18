package com.ruoyi.system.controller;

import java.util.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.domain.entity.SysDictData;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.file.FileUtils;
import com.ruoyi.system.service.ISysDictDataService;
import com.ruoyi.system.shop.*;

@RestController
@RequestMapping("/miniapp")
public class MiniAppMaterialController {
    private final ShopService shop;
    private final PrivateStorage storage;
    private final ISysDictDataService dicts;
    public MiniAppMaterialController(ShopService shop,PrivateStorage storage,ISysDictDataService dicts){this.shop=shop;this.storage=storage;this.dicts=dicts;}
    @PreAuthorize("@ss.hasAnyRoles('parent,teacher,admin')")
    @GetMapping("/material/dict") public AjaxResult dict(){
        var s=new SysDictData();s.setDictType("edu_subject");s.setStatus("0");
        var g=new SysDictData();g.setDictType("edu_grade");g.setStatus("0");
        var t=new SysDictData();t.setDictType("edu_material_type");t.setStatus("0");
        return AjaxResult.success(Map.of("subjects",dicts.selectDictDataList(s),"grades",dicts.selectDictDataList(g),"materialTypes",dicts.selectDictDataList(t)));
    }
    @PreAuthorize("@ss.hasRole('parent')")
    @GetMapping("/parent/material/list") public AjaxResult list(@RequestParam Map<String,String> q){return AjaxResult.success(shop.products(q,SecurityUtils.getUserId(),false,false));}
    @PreAuthorize("@ss.hasRole('parent')")
    @GetMapping("/parent/material/{id}") public AjaxResult detail(@PathVariable long id){return AjaxResult.success(shop.product(id,SecurityUtils.getUserId(),false));}
    @PreAuthorize("@ss.hasRole('parent')")
    @GetMapping("/parent/material/orders") public AjaxResult orders(@RequestParam Map<String,String> q){return AjaxResult.success(shop.orders(q,SecurityUtils.getUserId(),false));}
    @PreAuthorize("@ss.hasRole('parent')")
    @PostMapping("/parent/material/orders") public AjaxResult create(@RequestBody Map<String,Object> body){return AjaxResult.success(shop.createOrder(SecurityUtils.getUserId(),body));}
    @PreAuthorize("@ss.hasRole('parent')")
    @GetMapping("/parent/material/orders/{id}") public AjaxResult order(@PathVariable long id){return AjaxResult.success(shop.order(id,SecurityUtils.getUserId(),false));}
    @PreAuthorize("@ss.hasRole('parent')")
    @PostMapping("/parent/material/orders/{id}/payment") public AjaxResult payment(@PathVariable long id){return AjaxResult.success(shop.preparePayment(SecurityUtils.getUserId(),id));}
    @PreAuthorize("@ss.hasRole('parent')")
    @PostMapping("/parent/material/orders/{id}/free-claim") public AjaxResult claimFree(@PathVariable long id){return AjaxResult.success(shop.claimFree(SecurityUtils.getUserId(),id));}
    @PreAuthorize("@ss.hasRole('parent')")
    @PostMapping("/parent/material/orders/{id}/cancel") public AjaxResult cancel(@PathVariable long id){return AjaxResult.success(shop.cancel(SecurityUtils.getUserId(),id));}
    @PreAuthorize("@ss.hasRole('parent')")
    @PostMapping("/parent/material/orders/{id}/receive") public AjaxResult receive(@PathVariable long id){return AjaxResult.success(shop.receive(SecurityUtils.getUserId(),id));}
    @PreAuthorize("@ss.hasRole('parent')")
    @PostMapping("/parent/material/buy") public AjaxResult disabledBuy(){return AjaxResult.error("购买流程已升级，请创建待支付订单后确认模拟支付");}
    @PreAuthorize("@ss.hasRole('parent')")
    @GetMapping("/parent/material/download/{id}") public void download(@PathVariable long id,HttpServletResponse response)throws Exception {
        var path=shop.download(SecurityUtils.getUserId(),id);
        response.setContentType("application/pdf");FileUtils.setAttachmentResponseHeader(response,"material-"+id+".pdf");
        java.nio.file.Files.copy(path,response.getOutputStream());
    }
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')")
    @GetMapping("/teacher/material/list") public AjaxResult myUploads(@RequestParam Map<String,String> q){return AjaxResult.success(shop.products(q,SecurityUtils.getUserId(),true,true));}
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')")
    @PostMapping("/teacher/material") public AjaxResult draft(@RequestBody Map<String,Object> body){body.remove("materialId");return AjaxResult.success(shop.saveProduct(SecurityUtils.getUserId(),body,true));}
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')")
    @PutMapping("/teacher/material") public AjaxResult editDraft(@RequestBody Map<String,Object> body){ShopService.require(body.get("materialId")!=null,"资料ID不能为空");return AjaxResult.success(shop.saveProduct(SecurityUtils.getUserId(),body,true));}
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')")
    @DeleteMapping("/teacher/material/{id}") public AjaxResult deleteDraft(@PathVariable long id){shop.deleteProduct(SecurityUtils.getUserId(),id,true);return AjaxResult.success();}
    @PreAuthorize("@ss.hasAnyRoles('teacher,admin')")
    @PostMapping("/teacher/material/file") public AjaxResult file(@RequestParam("file") MultipartFile file)throws Exception{return AjaxResult.success(storage.upload(SecurityUtils.getUserId(),file));}
}
