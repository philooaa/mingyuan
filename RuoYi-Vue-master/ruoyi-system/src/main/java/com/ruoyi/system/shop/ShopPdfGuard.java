package com.ruoyi.system.shop;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Public PDFs (including legacy /profile PDFs) are served only by an authorized order download. */
@Component
public class ShopPdfGuard implements HandlerInterceptor {
    private final ShopRepository db;
    public ShopPdfGuard(ShopRepository db){this.db=db;}
    public static boolean forbidden(String uri,String resource,String fileName) {
        String raw=uri+" "+(resource==null?"":resource)+" "+(fileName==null?"":fileName);
        try{for(int i=0;i<3;i++)raw=URLDecoder.decode(raw,StandardCharsets.UTF_8);}
        catch(IllegalArgumentException e){return true;}
        raw=raw.toLowerCase(Locale.ROOT);
        return raw.contains(".pdf")||raw.contains("shop/")||raw.contains("..")||raw.contains("\\");
    }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler)throws Exception {
        String uri=request.getRequestURI(),resource=request.getParameter("resource"),file=request.getParameter("fileName");
        // Homework attachments (publish/answer/review files) are exempt from the shop PDF guard.
        if(uri.startsWith("/profile/")&&!db.rows("SELECT file_id FROM edu_homework_file WHERE file_path=? LIMIT 1",uri).isEmpty())return true;
        boolean denied=forbidden(uri,resource,file);
        if(!denied&&uri.contains("/profile/"))denied=!db.rows("SELECT material_id FROM edu_material WHERE file_path=? LIMIT 1",uri).isEmpty()
            ||!db.rows("SELECT item_id FROM edu_material_order_item WHERE asset_path=? LIMIT 1",uri).isEmpty();
        if(!denied&&resource!=null)denied=!db.rows("SELECT material_id FROM edu_material WHERE file_path=? LIMIT 1",resource).isEmpty()
            ||!db.rows("SELECT item_id FROM edu_material_order_item WHERE asset_path=? LIMIT 1",resource).isEmpty();
        if(denied){response.setStatus(403);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"code\":403,\"msg\":\"资料PDF需通过已购买订单下载\"}");return false;}
        return true;
    }
}
