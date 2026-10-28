package com.ruoyi.system.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Scope database/path redaction to finance and its protected legacy enrollment entrances. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(
    assignableTypes = {
      EduTuitionController.class,
      EduCouponController.class,
      EduReferralController.class,
      MiniAppReferralController.class,
      MiniAppTuitionController.class,
      TuitionReceiptController.class,
      FinanceEvidenceController.class,
      EduEnrollmentController.class,
      MiniAppParentController.class
    })
public class FinanceExceptionHandler {
  @ExceptionHandler(DataAccessException.class)
  public AjaxResult database(DataAccessException error) {
    org.slf4j.LoggerFactory.getLogger(getClass())
        .warn("财务数据操作冲突或数据库不可用，异常类型={}", error.getClass().getSimpleName());
    return AjaxResult.error("财务数据操作未完成，请刷新核对已有记录或联系管理员；不要重复收款");
  }

  @ExceptionHandler(IOException.class)
  public AjaxResult file(IOException error) {
    return AjaxResult.error("私有文件操作未完成，请重试或联系管理员检查存储配置");
  }
}
