package com.ruoyi.system.controller;

import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.core.page.TableDataInfo;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Mapper-backed miniapp responses expose the same array contract as the schedule entity. */
@RestControllerAdvice(basePackages = "com.ruoyi.system.controller")
public class ScheduleSnapshotResponseAdvice implements ResponseBodyAdvice<Object> {
    @Override public boolean supports(MethodParameter method, Class<? extends HttpMessageConverter<?>> converter) { return true; }
    @Override public Object beforeBodyWrite(Object body, MethodParameter method, MediaType type,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        normalize(body); return body;
    }
    @SuppressWarnings("unchecked")
    private void normalize(Object value) {
        if (value instanceof TableDataInfo table) normalize(table.getRows());
        else if (value instanceof Map<?,?> raw) {
            Map<Object,Object> map = (Map<Object,Object>) raw;
            if (map.containsKey("customDates") && map.get("customDates") == null) map.put("customDates", List.of());
            if (map.get("customDates") instanceof String text) map.put("customDates", JSON.parseArray(text, String.class));
            for (Object item : map.values()) normalize(item);
        } else if (value instanceof Collection<?> list) for (Object item : list) normalize(item);
    }
}
