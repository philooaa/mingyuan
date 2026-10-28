package com.ruoyi.system.controller;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.common.utils.file.FileUtils;
import com.ruoyi.system.finance.TuitionReceiptService;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
public class TuitionReceiptController {
  private final TuitionReceiptService service;

  public TuitionReceiptController(TuitionReceiptService service) {
    this.service = service;
  }

  private TableDataInfo page(Map<String, Object> result) {
    var data = com.ruoyi.system.finance.FinanceRules.dto(result);
    var p = new TableDataInfo((List<?>) data.get("rows"), ((Number) data.get("total")).longValue());
    p.setCode(200);
    p.setMsg("查询成功");
    return p;
  }

  @PreAuthorize("@ss.hasPermi('system:tuitionReceipt:list')")
  @GetMapping("/system/tuition/receipts/list")
  public TableDataInfo list(@RequestParam Map<String, String> q) {
    return page(service.list(SecurityUtils.getUserId(), true, q));
  }

  @PreAuthorize("@ss.hasRole('parent')")
  @GetMapping("/miniapp/parent/tuition/receipts")
  public TableDataInfo ownList(@RequestParam Map<String, String> q) {
    return page(service.list(SecurityUtils.getUserId(), false, q));
  }

  @PreAuthorize("@ss.hasPermi('system:tuitionReceipt:issue') and @ss.hasRole('admin')")
  @Log(
      title = "课程收据开具",
      businessType = BusinessType.INSERT,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PostMapping("/system/tuition/receipts/{paymentId}/issue")
  public AjaxResult issue(@PathVariable long paymentId) {
    return AjaxResult.success(
        com.ruoyi.system.finance.FinanceRules.dto(
            service.issue(paymentId, SecurityUtils.getUserId())));
  }

  @PreAuthorize("@ss.hasPermi('system:tuitionReceipt:issue') and @ss.hasRole('admin')")
  @Log(
      title = "课程收据生成重试",
      businessType = BusinessType.UPDATE,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PostMapping("/system/tuition/receipts/{id}/retry")
  public AjaxResult retry(@PathVariable long id) {
    return AjaxResult.success(
        com.ruoyi.system.finance.FinanceRules.dto(service.retry(id, SecurityUtils.getUserId())));
  }

  @PreAuthorize("@ss.hasPermi('system:tuitionReceipt:replace') and @ss.hasRole('admin')")
  @Log(
      title = "课程收据作废补开",
      businessType = BusinessType.UPDATE,
      isSaveRequestData = false,
      isSaveResponseData = false)
  @PostMapping("/system/tuition/receipts/{id}/replace")
  public AjaxResult replace(@PathVariable long id, @RequestBody Map<String, Object> b) {
    return AjaxResult.success(
        com.ruoyi.system.finance.FinanceRules.dto(
            service.replace(id, SecurityUtils.getUserId(), Objects.toString(b.get("reason"), ""))));
  }

  @PreAuthorize("@ss.hasPermi('system:tuitionReceipt:download')")
  @GetMapping("/system/tuition/receipts/{id}/file")
  public void file(@PathVariable long id, HttpServletResponse response) throws Exception {
    send(service.download(id, SecurityUtils.getUserId(), true), "receipt-" + id + ".pdf", response);
  }

  @PreAuthorize("@ss.hasRole('parent')")
  @GetMapping("/miniapp/parent/tuition/receipts/{id}/file")
  public void ownFile(@PathVariable long id, HttpServletResponse response) throws Exception {
    send(
        service.download(id, SecurityUtils.getUserId(), false), "receipt-" + id + ".pdf", response);
  }

  static void send(Path path, String filename, HttpServletResponse response) throws Exception {
    response.setContentType(
        filename.endsWith(".pdf")
            ? "application/pdf"
            : filename.endsWith(".png") ? "image/png" : "image/jpeg");
    response.setHeader("Cache-Control", "private, no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
    FileUtils.setAttachmentResponseHeader(response, filename);
    response.setContentLengthLong(Files.size(path));
    Files.copy(path, response.getOutputStream());
  }
}
