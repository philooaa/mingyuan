package com.ruoyi.system.shop;

import java.math.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.finance.FinanceRules;

@Service
public class ShopService {
    private final ShopRepository db;
    private final PrivateStorage storage;
    private final boolean mockEnabled;
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private static final List<String> ADDRESS=List.of("receiver_name","receiver_phone","province_code","city_code","district_code","province_name","city_name","district_name","detail_address","postal_code");
    private static final List<String> PRODUCT=List.of("title","subtitle","subject_code","grade_code","material_type","textbook_version","delivery_type","price","intro","detail_text","cover_url","unit","purchase_limit","shipping_fee","ship_from","dispatch_days","file_path","file_name","file_size","shelf_status","sort_order");
    @Autowired public ShopService(ShopRepository db,PrivateStorage storage,Environment env) {
        this(db,storage,Arrays.stream(env.getActiveProfiles()).anyMatch(p->p.equals("local")||p.equals("test"))
            && Arrays.stream(env.getActiveProfiles()).noneMatch(p->p.equals("prod")||p.equals("production"))
            && env.getProperty("payment.mode","disabled").equalsIgnoreCase("mock"));
    }
    public ShopService(ShopRepository db,PrivateStorage storage,boolean mockEnabled) {
        this.db=db;this.storage=storage;this.mockEnabled=mockEnabled;
    }
    private <T> T transaction(Supplier<T> operation) {
        for(int attempt=0;;attempt++) {
            try{return db.transactions().execute(s->operation.get());}
            catch(PessimisticLockingFailureException e){if(attempt>=2)throw new ServiceException("操作繁忙，请重试");}
        }
    }
    public static String text(Object value) {return value==null?"":String.valueOf(value).trim();}
    private static String str(Map<String,Object> m,String key) {return text(m.get(key));}
    static long id(Object value) {try {long n=Long.parseLong(text(value));if(n<1)throw new NumberFormatException();return n;}catch(Exception e){throw new ServiceException("ID无效");}}
    private static int integer(Object value,int min,int max,String label) {
        try {int n=new BigDecimal(text(value)).intValueExact();if(n<min||n>max)throw new ArithmeticException();return n;}
        catch(Exception e){throw new ServiceException(label+"必须是"+min+"至"+max+"之间的整数");}
    }
    private static BigDecimal money(Object value) {
        try {BigDecimal n=new BigDecimal(text(value)).setScale(2,RoundingMode.UNNECESSARY);
            if(n.signum()<0||n.compareTo(new BigDecimal("21474836.47"))>0)throw new ArithmeticException();return n;
        }catch(Exception e){throw new ServiceException("金额无效，请使用非负两位小数");}
    }
    public static void require(boolean condition,String message) {if(!condition)throw new ServiceException(message);}
    private static Timestamp now() {return Timestamp.valueOf(LocalDateTime.now(ZONE));}
    private static String code(String prefix) {return prefix+UUID.randomUUID().toString().replace("-","").substring(0,31);}
    private static Map<String,Object> fields(Map<String,Object> body,List<String> columns) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String column:columns)if(body.containsKey(ShopRepository.camel(column)))result.put(column,body.get(ShopRepository.camel(column)));
        return result;
    }
    private Map<String,Object> lockOrder(long order,long user,boolean admin) {
        return db.one("SELECT * FROM edu_material_order WHERE order_id=?"+(admin?"":" AND parent_id=?")+" FOR UPDATE",
            admin?new Object[]{order}:new Object[]{order,user});
    }
    private void userLock(long user) {db.one("SELECT user_id FROM sys_user WHERE user_id=? FOR UPDATE",user);}
    private void log(long order,String event,Map<String,Object> before,String next,String pay,long actor,String kind,String remark) {
        Map<String,Object> log=new LinkedHashMap<>();
        log.put("order_id",order);log.put("event_type",event);log.put("from_order_status",before.get("orderStatus"));
        log.put("to_order_status",next);log.put("from_pay_status",before.get("payStatus"));log.put("to_pay_status",pay);
        log.put("operator_id",actor==0?null:actor);log.put("operator_type",kind);log.put("remark",remark);log.put("create_time",now());
        db.insert("edu_material_order_log",log);
    }
    public Map<String,Object> createOrder(long user,Map<String,Object> body) {
        long material=id(body.get("materialId"));int quantity=integer(body.get("quantity"),1,99,"数量");
        String key=str(body,"clientRequestId");require(key.matches("[A-Za-z0-9_-]{1,64}"),"请提供有效的请求编号");
        BigDecimal expected=money(body.get("expectedPrice"));require(str(body,"buyerRemark").length()<=500,"留言不能超过500字");
        Map<String,Object> inputAddress=new LinkedHashMap<>();
        if(body.get("address") instanceof Map<?,?> raw)for(String field:ADDRESS)inputAddress.put(ShopRepository.camel(field),text(raw.get(ShopRepository.camel(field))));
        String fingerprint=hash(com.alibaba.fastjson2.JSON.toJSONString(new TreeMap<>(Map.of(
            "materialId",String.valueOf(material),"quantity",quantity,"expectedPrice",expected.toPlainString(),
            "addressId",text(body.get("addressId")),"address",new TreeMap<>(inputAddress),"buyerRemark",str(body,"buyerRemark")))));
        return transaction(()->{
            // Serialize submissions for one buyer: idempotency, digital entitlements, and address defaults.
            userLock(user);
            var existing=db.rows("SELECT order_id,request_hash FROM edu_material_order WHERE parent_id=? AND client_request_id=?",user,key);
            if(!existing.isEmpty()) {
                require(fingerprint.equals(existing.get(0).get("requestHash")),"同一请求编号不能提交不同内容");
                return order(id(existing.get(0).get("orderId")),user,false);
            }
            var p=db.one("SELECT * FROM edu_material WHERE material_id=? FOR UPDATE",material);
            require("0".equals(str(p,"delFlag"))&&"0".equals(str(p,"status"))&&"1".equals(str(p,"shelfStatus")),"商品不存在或已下架");
            require(money(p.get("price")).compareTo(expected)==0,"商品价格已变化，请重新确认");
            boolean physical="PHYSICAL".equals(p.get("deliveryType"));
            require(quantity<=integer(p.get("purchaseLimit"),1,99,"限购数量"),"超过单次限购数量");
            if(!physical) {
                require(quantity==1,"电子资料数量必须为1");
                var rights=db.rows("SELECT o.order_id,o.order_status,o.request_hash FROM edu_material_order o JOIN edu_material_order_item i ON o.order_id=i.order_id WHERE o.parent_id=? AND i.material_id=? AND (o.pay_status='1' OR (o.order_status='WAIT_PAY' AND o.expire_time>?)) ORDER BY o.order_id DESC LIMIT 1",user,material,now());
                if(!rights.isEmpty()) {
                    var previous=rights.get(0);require("WAIT_PAY".equals(previous.get("orderStatus")),"电子资料已购买，请到订单下载");
                    require(fingerprint.equals(previous.get("requestHash")),"已有待支付订单，请先完成或取消");
                    return order(id(previous.get("orderId")),user,false);
                }
            }
            Map<String,Object> address=new LinkedHashMap<>();
            if(physical) {
                if(body.get("addressId")!=null)address.putAll(db.one("SELECT * FROM edu_user_address WHERE address_id=? AND user_id=? AND del_flag='0'",id(body.get("addressId")),user));
                else address.putAll(inputAddress);
                validateAddress(address);
                require(db.jdbc().update("UPDATE edu_material SET stock_locked=stock_locked+?,version=version+1 WHERE material_id=? AND stock_quantity-stock_locked>=?",quantity,material,quantity)==1,"库存不足");
            }
            BigDecimal goods=money(p.get("price")).multiply(BigDecimal.valueOf(quantity));
            BigDecimal shipping=physical?money(p.get("shippingFee")):BigDecimal.ZERO.setScale(2);
            BigDecimal payable=money(goods.add(shipping));
            Map<String,Object> values=new LinkedHashMap<>();
            values.put("order_code",code("O"));values.put("material_id",material);values.put("parent_id",user);
            values.put("goods_amount",goods);values.put("shipping_amount",shipping);values.put("payable_amount",payable);
            values.put("amount",BigDecimal.ZERO);values.put("order_status","WAIT_PAY");values.put("pay_status","0");
            values.put("total_quantity",quantity);values.put("delivery_type",p.get("deliveryType"));values.put("client_request_id",key);
            values.put("request_hash",fingerprint);values.put("stock_state",physical?"RESERVED":"NOT_APPLICABLE");
            values.put("expire_time",Timestamp.valueOf(LocalDateTime.now(ZONE).plusMinutes(30)));values.put("create_time",now());
            values.put("buyer_remark",str(body,"buyerRemark"));values.put("wx_transaction_id",null);values.put("pay_way","0");
            if(physical) {values.putAll(fields(address,ADDRESS));if(body.get("addressId")!=null)values.put("address_id",id(body.get("addressId")));}
            long order=db.insert("edu_material_order",values);
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("order_id",order);item.put("material_id",material);item.put("material_code",p.get("materialCode"));
            item.put("material_title",p.get("title"));item.put("cover_url",p.get("coverUrl"));item.put("subject_code",p.get("subjectCode"));
            item.put("grade_code",p.get("gradeCode"));item.put("subject_label",label("edu_subject",str(p,"subjectCode")));item.put("grade_label",label("edu_grade",str(p,"gradeCode")));
            item.put("unit",p.get("unit"));item.put("delivery_type",p.get("deliveryType"));item.put("unit_price",p.get("price"));
            item.put("quantity",quantity);item.put("line_amount",goods);item.put("create_time",now());
            if(!physical) {item.put("asset_path",p.get("filePath"));item.put("asset_name",p.get("fileName"));item.put("asset_size",p.get("fileSize"));}
            db.insert("edu_material_order_item",item);
            Map<String,Object> payment=new LinkedHashMap<>();
            payment.put("order_id",order);payment.put("out_trade_no",code("P"));payment.put("channel",payable.signum()==0?"FREE":"MOCK");
            payment.put("payment_status","CREATED");payment.put("amount_cent",payable.movePointRight(2).intValueExact());
            payment.put("create_time",now());payment.put("update_time",now());db.insert("edu_material_payment",payment);
            log(order,"CREATE",Map.of(),"WAIT_PAY","0",user,"USER",null);
            return order(order,user,false);
        });
    }
    private static String hash(String input) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private static void validateAddress(Map<String,Object> a) {
        for(String k:List.of("receiverName","receiverPhone","provinceName","cityName","districtName","detailAddress")) {
            int max=k.equals("detailAddress")?255:k.equals("receiverPhone")?32:50;
            require(!str(a,k).isBlank()&&str(a,k).length()<=max,"请完整填写收货人、电话及省市区详细地址");
        }
        require(str(a,"receiverPhone").matches("[+0-9() -]{6,32}"),"电话格式无效");
        for(String k:List.of("provinceCode","cityCode","districtCode"))require(str(a,k).length()<=12,"行政区编码过长");
        require(str(a,"postalCode").length()<=10,"邮编过长");
    }
    public Map<String,Object> preparePayment(long user,long order) {
        require(mockEnabled,"此环境未启用模拟支付");
        var o=order(order,user,false);require("WAIT_PAY".equals(o.get("orderStatus")),"订单不可支付");
        require(!expired(o),"订单已超时，请重新下单");
        return Map.of("mode","MOCK","orderId",String.valueOf(order));
    }
    private static boolean expired(Map<String,Object> o) {
        return !str(o,"expireTime").isBlank()&&!LocalDateTime.parse(str(o,"expireTime"),DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).isAfter(LocalDateTime.now(ZONE));
    }
    public Map<String,Object> pay(long user,long order) {
        require(mockEnabled,"此环境未启用模拟支付");
        var result=transaction(()->{
            var o=lockOrder(order,user,false);var payment=db.one("SELECT * FROM edu_material_payment WHERE order_id=? FOR UPDATE",order);
            if("1".equals(o.get("payStatus")))return order(order,user,false);
            require("WAIT_PAY".equals(o.get("orderStatus"))&&"0".equals(o.get("payStatus")),"订单不可支付");
            if(expired(o)){close(o,order,user,"CLOSED","订单超时");return order(order,user,false);}
            require(List.of("MOCK","FREE").contains(payment.get("channel")),"此订单不能模拟支付");
            int cents=money(o.get("payableAmount")).movePointRight(2).intValueExact();
            require(cents==integer(payment.get("amountCent"),0,Integer.MAX_VALUE,"支付金额"),"订单支付金额不一致");
            var item=db.one("SELECT * FROM edu_material_order_item WHERE order_id=?",order);
            boolean physical="PHYSICAL".equals(o.get("deliveryType"));int qty=integer(item.get("quantity"),1,99,"数量");long material=id(item.get("materialId"));
            db.one("SELECT material_id FROM edu_material WHERE material_id=? FOR UPDATE",material);
            if(physical)require(db.jdbc().update("UPDATE edu_material SET stock_quantity=stock_quantity-?,stock_locked=stock_locked-?,sale_count=sale_count+?,version=version+1 WHERE material_id=? AND stock_locked>=? AND stock_quantity>=?",qty,qty,qty,material,qty,qty)==1,"库存状态异常");
            else db.jdbc().update("UPDATE edu_material SET sale_count=sale_count+1,version=version+1 WHERE material_id=?",material);
            String next=physical?"WAIT_SHIP":"COMPLETED";
            db.jdbc().update("UPDATE edu_material_payment SET payment_status='SUCCESS',paid_amount_cent=?,mock_transaction_id=?,success_time=?,update_time=? WHERE order_id=?",cents,code("M"),now(),now(),order);
            db.jdbc().update("UPDATE edu_material_order SET pay_status='1',amount=payable_amount,pay_time=?,order_status=?,stock_state=?,finish_time=?,update_time=? WHERE order_id=?",now(),next,physical?"CONSUMED":"NOT_APPLICABLE",physical?null:now(),now(),order);
            log(order,"PAY_SUCCESS",o,next,"1",user,"PAYMENT","模拟支付，无真实扣款");return order(order,user,false);
        });
        require(!"CLOSED".equals(result.get("orderStatus")),"订单已超时，请重新下单");return result;
    }
    private void close(Map<String,Object> o,long order,long user,String next,String reason) {
        db.one("SELECT order_id FROM edu_material_payment WHERE order_id=? FOR UPDATE",order);
        if("RESERVED".equals(o.get("stockState"))) {
            var item=db.one("SELECT material_id,quantity FROM edu_material_order_item WHERE order_id=?",order);int qty=integer(item.get("quantity"),1,99,"数量");
            require(db.jdbc().update("UPDATE edu_material SET stock_locked=stock_locked-?,version=version+1 WHERE material_id=? AND stock_locked>=?",qty,id(item.get("materialId")),qty)==1,"库存预占状态异常");
        }
        db.jdbc().update("UPDATE edu_material_order SET order_status=?,stock_state=?,close_reason=?,cancel_time=?,update_time=? WHERE order_id=?",next,"RESERVED".equals(o.get("stockState"))?"RELEASED":"NOT_APPLICABLE",reason,now(),now(),order);
        db.jdbc().update("UPDATE edu_material_payment SET payment_status='CLOSED',close_time=?,update_time=? WHERE order_id=?",now(),now(),order);
        log(order,next.equals("CLOSED")?"EXPIRE":"CANCEL",o,next,"0",user,user==0?"SYSTEM":"USER",reason);
    }
    public Map<String,Object> cancel(long user,long order) {
        return transaction(()->{var o=lockOrder(order,user,false);
            if(List.of("CANCELLED","CLOSED").contains(o.get("orderStatus")))return order(order,user,false);
            require("WAIT_PAY".equals(o.get("orderStatus"))&&"0".equals(o.get("payStatus")),"只有未支付订单可以取消");
            close(o,order,user,"CANCELLED","用户取消");return order(order,user,false);});
    }
    @Scheduled(fixedDelay=60000) public void expireOrders() {
        for(var row:db.rows("SELECT order_id FROM edu_material_order WHERE order_status='WAIT_PAY' AND expire_time<=? ORDER BY order_id LIMIT 200",now())) {
            long order=id(row.get("orderId"));transaction(()->{var o=lockOrder(order,0,true);if("WAIT_PAY".equals(o.get("orderStatus"))&&expired(o))close(o,order,0,"CLOSED","订单超时");return null;});
        }
    }
    public Map<String,Object> ship(long actor,long order,Map<String,Object> body) {
        for(var e:Map.of("carrierCode",32,"carrierName",50,"trackingNo",64).entrySet())require(!str(body,e.getKey()).isBlank()&&str(body,e.getKey()).length()<=e.getValue(),"请填写有效快递公司和运单号");
        return transaction(()->{var o=lockOrder(order,actor,true);
            if(List.of("WAIT_RECEIVE","COMPLETED").contains(o.get("orderStatus"))) {
                var existing=db.one("SELECT * FROM edu_material_shipment WHERE order_id=?",order);
                for(String k:List.of("carrierCode","carrierName","trackingNo"))require(str(body,k).equals(str(existing,k)),"订单已经发货，不能重复更改物流");
                return order(order,actor,true);
            }
            require("PHYSICAL".equals(o.get("deliveryType"))&&"WAIT_SHIP".equals(o.get("orderStatus"))&&"1".equals(o.get("payStatus"))&&"NONE".equals(o.get("aftersaleStatus")),"只允许已支付待发货纸质订单发货");
            var shipment=fields(body,List.of("carrier_code","carrier_name","tracking_no"));
            shipment.put("order_id",order);shipment.put("ship_by",actor);shipment.put("ship_time",now());shipment.put("create_time",now());shipment.put("shipment_status","SHIPPED");db.insert("edu_material_shipment",shipment);
            db.jdbc().update("UPDATE edu_material_order SET order_status='WAIT_RECEIVE',update_time=? WHERE order_id=?",now(),order);
            log(order,"SHIP",o,"WAIT_RECEIVE","1",actor,"ADMIN",null);return order(order,actor,true);});
    }
    public Map<String,Object> receive(long user,long order) {
        return transaction(()->{var o=lockOrder(order,user,false);
            require("PHYSICAL".equals(o.get("deliveryType"))&&"1".equals(o.get("payStatus")),"订单不可确认收货");
            if("COMPLETED".equals(o.get("orderStatus")))return order(order,user,false);
            require("WAIT_RECEIVE".equals(o.get("orderStatus")),"订单尚未发货");
            require(db.jdbc().update("UPDATE edu_material_shipment SET shipment_status='RECEIVED',receive_time=? WHERE order_id=?",now(),order)==1,"缺少发货记录");
            db.jdbc().update("UPDATE edu_material_order SET order_status='COMPLETED',finish_time=?,update_time=? WHERE order_id=?",now(),now(),order);
            log(order,"RECEIVE",o,"COMPLETED","1",user,"USER",null);return order(order,user,false);});
    }
    public Map<String,Object> refund(long actor,long order,String reason) {
        require(mockEnabled,"此环境未启用模拟退款");require(!reason.isBlank()&&reason.length()<=255,"请填写255字以内的退款原因");
        return transaction(()->{var o=lockOrder(order,actor,true);var payment=db.one("SELECT * FROM edu_material_payment WHERE order_id=? FOR UPDATE",order);
            require(List.of("MOCK","FREE").contains(payment.get("channel")),"真实支付订单不能模拟退款");
            if("REFUNDED".equals(o.get("aftersaleStatus")))return order(order,actor,true);
            require("1".equals(o.get("payStatus"))&&List.of("WAIT_SHIP","COMPLETED").contains(o.get("orderStatus")),"订单不可退款");
            require(db.rows("SELECT order_id FROM edu_material_shipment WHERE order_id=?",order).isEmpty(),"已发货订单暂不支持退款");
            boolean physical="PHYSICAL".equals(o.get("deliveryType"));
            if(physical){require("CONSUMED".equals(o.get("stockState")),"订单库存状态异常");var item=db.one("SELECT * FROM edu_material_order_item WHERE order_id=?",order);
                db.jdbc().update("UPDATE edu_material SET stock_quantity=stock_quantity+?,version=version+1 WHERE material_id=?",item.get("quantity"),id(item.get("materialId")));}
            db.jdbc().update("UPDATE edu_material_order SET pay_status='2',aftersale_status='REFUNDED',refunded_amount=amount,refund_time=?,refund_reason=?,stock_state=?,update_time=? WHERE order_id=?",now(),reason,physical?"RESTORED":"NOT_APPLICABLE",now(),order);
            log(order,"MOCK_REFUND",o,str(o,"orderStatus"),"2",actor,"ADMIN","模拟全额退款："+reason);return order(order,actor,true);});
    }
    public Map<String,Object> order(long order,long user,boolean admin) {
        var o=db.one("SELECT o.*,"+FinanceRules.parentNameSql("u")+" AS parent_name FROM edu_material_order o LEFT JOIN sys_user u ON u.user_id=o.parent_id WHERE o.order_id=?"+(admin?"":" AND o.parent_id=?"),admin?new Object[]{order}:new Object[]{order,user});
        Map<String,Object> dto=new LinkedHashMap<>();
        for(String k:List.of("orderId","orderCode","parentId","parentName","orderStatus","payStatus","aftersaleStatus","goodsAmount","shippingAmount","payableAmount","amount","refundedAmount","totalQuantity","deliveryType","receiverName","receiverPhone","provinceName","cityName","districtName","detailAddress","buyerRemark","createTime","expireTime","payTime"))dto.put(k,o.get(k));
        if(admin)dto.put("adminRemark",o.get("adminRemark"));
        dto.put("items",db.rows("SELECT item_id,material_id,material_title,cover_url,unit,delivery_type,unit_price,quantity,line_amount,subject_code,grade_code,subject_label,grade_label FROM edu_material_order_item WHERE order_id=?",order));
        var payments=db.rows("SELECT channel,payment_status,wx_transaction_id,success_time FROM edu_material_payment WHERE order_id=?",order);dto.put("payment",payments.isEmpty()?null:payments.get(0));
        var shipments=db.rows("SELECT carrier_code,carrier_name,tracking_no,ship_time,receive_time FROM edu_material_shipment WHERE order_id=?",order);dto.put("shipment",shipments.isEmpty()?null:shipments.get(0));
        dto.put("logs",db.rows("SELECT log_id,event_type,from_order_status,to_order_status,from_pay_status,to_pay_status,operator_type,create_time FROM edu_material_order_log WHERE order_id=? ORDER BY log_id",order));return dto;
    }
    public Path download(long user,long order) {
        var o=order(order,user,false);
        require("1".equals(o.get("payStatus"))&&"DIGITAL".equals(o.get("deliveryType"))&&"NONE".equals(o.get("aftersaleStatus")),"未购买或已退款，无法下载");
        var payment=db.one("SELECT channel,payment_status,amount_cent,paid_amount_cent,currency,wx_transaction_id FROM edu_material_payment WHERE order_id=?",order);
        int paid=money(o.get("amount")).movePointRight(2).intValueExact();
        require("SUCCESS".equals(payment.get("paymentStatus"))&&"CNY".equals(payment.get("currency"))
            &&paid==integer(payment.get("amountCent"),0,Integer.MAX_VALUE,"支付金额")
            &&paid==integer(payment.get("paidAmountCent"),0,Integer.MAX_VALUE,"实付金额"),"付款凭证不完整，请联系管理员");
        switch(str(payment,"channel")) {
            case "MOCK" -> require(mockEnabled,"当前环境未开放模拟订单下载");
            case "WECHAT" -> require(!str(payment,"wxTransactionId").isBlank(),"历史微信付款凭证不完整，请联系管理员");
            case "FREE" -> require(paid==0,"免费领取金额异常，请联系管理员");
            default -> throw new ServiceException("付款渠道无效，请联系管理员");
        }
        return storage.resolve(str(db.one("SELECT asset_path FROM edu_material_order_item WHERE order_id=?",order),"assetPath"));
    }
    public Map<String,Object> product(long material,long user,boolean admin) {
        var p=db.one("SELECT * FROM edu_material WHERE material_id=? AND del_flag='0'"+(admin?"":" AND status='0' AND shelf_status='1'"),material);
        Map<String,Object> dto=new LinkedHashMap<>();
        for(String key:List.of("materialId","materialCode","title","subtitle","subjectCode","gradeCode","materialType","textbookVersion","deliveryType","price","intro","detailText","coverUrl","unit","stockQuantity","stockLocked","purchaseLimit","shippingFee","shipFrom","dispatchDays","shelfStatus","saleCount","sortOrder","version"))dto.put(key,p.get(key));
        dto.put("subjectName",label("edu_subject",str(p,"subjectCode")));dto.put("gradeName",label("edu_grade",str(p,"gradeCode")));
        dto.put("availableStock",((Number)p.get("stockQuantity")).intValue()-((Number)p.get("stockLocked")).intValue());
        if(admin)for(String key:List.of("filePath","fileName","fileSize"))dto.put(key,p.get(key));
        dto.put("images",db.rows("SELECT image_type,image_url,sort_order FROM edu_material_image WHERE material_id=? ORDER BY image_type,sort_order,image_id",material));
        dto.put("bought",user>0&&!db.rows("SELECT o.order_id FROM edu_material_order o JOIN edu_material_order_item i ON i.order_id=o.order_id WHERE o.parent_id=? AND i.material_id=? AND o.pay_status='1' LIMIT 1",user,material).isEmpty());
        return dto;
    }
    private String label(String type,String code) {
        var list=db.rows("SELECT dict_label FROM sys_dict_data WHERE dict_type=? AND dict_value=? AND status='0' LIMIT 1",type,code);
        return list.isEmpty()?code:str(list.get(0),"dictLabel");
    }
    public Map<String,Object> products(Map<String,String> q,long user,boolean admin,boolean mine) {
        List<Object> args=new ArrayList<>();StringBuilder where=new StringBuilder(" WHERE del_flag='0'");
        if(!admin&&!mine)where.append(" AND status='0' AND shelf_status='1'");
        if(mine){where.append(" AND upload_user_id=?");args.add(user);}
        filter(where,args,q,"title","title",true);filter(where,args,q,"subjectCode","subject_code",false);filter(where,args,q,"gradeCode","grade_code",false);
        if(admin||mine)filter(where,args,q,"shelfStatus","shelf_status",false);
        String sort=Map.of("default","sort_order DESC,material_id DESC","priceAsc","price ASC,material_id DESC","priceDesc","price DESC,material_id DESC","sales","sale_count DESC,material_id DESC").get(q.getOrDefault("sort","default"));
        require(sort!=null,"排序方式无效");
        long total=db.jdbc().queryForObject("SELECT COUNT(*) FROM edu_material"+where,Long.class,args.toArray());
        int page=integer(q.getOrDefault("pageNum","1"),1,1000000,"页码"),size=integer(q.getOrDefault("pageSize","20"),1,100,"每页数量");
        args.add(size);args.add((page-1)*size);List<Map<String,Object>> rows=new ArrayList<>();
        for(var row:db.rows("SELECT material_id FROM edu_material"+where+" ORDER BY "+sort+" LIMIT ? OFFSET ?",args.toArray()))rows.add(product(id(row.get("materialId")),user,admin||mine));
        return Map.of("rows",rows,"total",total);
    }
    private static void filter(StringBuilder where,List<Object> args,Map<String,String> q,String key,String column,boolean like) {
        if(q.get(key)!=null&&!q.get(key).isBlank()) {where.append(" AND ").append(column).append(like?" LIKE ?":"=?");args.add(like?"%"+q.get(key)+"%":q.get(key));}
    }
    public Map<String,Object> orders(Map<String,String> q,long user,boolean admin) {
        List<Object> args=new ArrayList<>();StringBuilder where=new StringBuilder(" WHERE 1=1");
        if(!admin){where.append(" AND o.parent_id=?");args.add(user);}
        filter(where,args,q,"orderCode","o.order_code",true);filter(where,args,q,"payStatus","o.pay_status",false);filter(where,args,q,"orderStatus","o.order_status",false);
        if(admin)filter(where,args,q,"parentName",FinanceRules.parentSearchSql("u"),true);
        if(q.get("title")!=null&&!q.get("title").isBlank()){where.append(" AND EXISTS(SELECT 1 FROM edu_material_order_item i WHERE i.order_id=o.order_id AND i.material_title LIKE ?)");args.add("%"+q.get("title")+"%");}
        for(String key:List.of("beginTime","endTime"))if(q.get(key)!=null&&!q.get(key).isBlank()){
            try{LocalDate date=LocalDate.parse(q.get(key).substring(0,10));where.append(key.equals("beginTime")?" AND o.create_time>=?":" AND o.create_time<?");args.add(Timestamp.valueOf(key.equals("beginTime")?date.atStartOfDay():date.plusDays(1).atStartOfDay()));}catch(Exception e){throw new ServiceException("日期格式无效");}
        }
        String from=" FROM edu_material_order o LEFT JOIN sys_user u ON u.user_id=o.parent_id";
        long total=db.jdbc().queryForObject("SELECT COUNT(*)"+from+where,Long.class,args.toArray());
        int page=integer(q.getOrDefault("pageNum","1"),1,1000000,"页码"),size=integer(q.getOrDefault("pageSize","20"),1,100,"每页数量");
        args.add(size);args.add((page-1)*size);List<Map<String,Object>> rows=new ArrayList<>();
        for(var row:db.rows("SELECT o.order_id"+from+where+" ORDER BY o.create_time DESC,o.order_id DESC LIMIT ? OFFSET ?",args.toArray())){
            var dto=order(id(row.get("orderId")),user,admin);
            if(admin) {
                dto.keySet().retainAll(Set.of("orderId","orderCode","parentId","parentName","orderStatus","payStatus","aftersaleStatus",
                    "goodsAmount","shippingAmount","payableAmount","amount","refundedAmount","totalQuantity","deliveryType",
                    "receiverName","receiverPhone","createTime","expireTime","payTime","items","payment","shipment"));
                String phone=str(dto,"receiverPhone").replaceAll("[^0-9]","");
                if(!str(dto,"receiverPhone").isBlank()) {
                    int visible=phone.length()>=11?3:phone.length()>=8?2:phone.length()>=3?1:0;
                    int suffix=phone.length()>=11?4:visible;
                    dto.put("receiverPhone",visible==0?"****":phone.substring(0,visible)+"****"+phone.substring(phone.length()-suffix));
                }
            }
            rows.add(dto);
        }
        return Map.of("rows",rows,"total",total);
    }
    public Map<String,Object> saveProduct(long user,Map<String,Object> body,boolean teacher) {
        return transaction(()->{
            boolean editing=body.get("materialId")!=null;long material=editing?id(body.get("materialId")):0;
            Map<String,Object> old=editing?db.one("SELECT * FROM edu_material WHERE material_id=? AND del_flag='0' FOR UPDATE",material):new LinkedHashMap<>();
            if(teacher&&editing){require(str(old,"uploadUserId").equals(String.valueOf(user)),"只能修改自己提交的草稿");require("2".equals(old.get("shelfStatus")),"已审核商品只能由管理员修改");}
            Map<String,Object> values=fields(body,PRODUCT);
            if(body.containsKey("materialType"))values.put("material_type",str(body,"materialType"));
            if(teacher){require(!body.containsKey("shelfStatus")||"2".equals(str(body,"shelfStatus")),"教师只能提交草稿");values.put("shelf_status","2");}
            if(!editing){values.putIfAbsent("title","");values.putIfAbsent("delivery_type","PHYSICAL");values.putIfAbsent("price","0.00");values.putIfAbsent("shelf_status","2");values.putIfAbsent("unit","份");values.putIfAbsent("purchase_limit",99);values.putIfAbsent("shipping_fee","0.00");}
            Map<String,Object> merged=new LinkedHashMap<>(old);values.forEach((k,v)->merged.put(ShopRepository.camel(k),v));
            require(str(merged,"title").length()<=100,"商品名称不能超过100字");
            require(List.of("PHYSICAL","DIGITAL").contains(str(merged,"deliveryType")),"交付方式无效");
            require(List.of("0","1","2").contains(str(merged,"shelfStatus")),"上下架状态无效");
            for(var e:Map.of("subtitle",200,"intro",1000,"textbookVersion",100,"materialType",30,"subjectCode",30,"gradeCode",30,"unit",20,"shipFrom",200).entrySet())require(str(merged,e.getKey()).length()<=e.getValue(),"商品字段过长："+e.getKey());
            if(body.containsKey("materialType")&&!str(body,"materialType").isBlank())
                require(!db.rows("SELECT dict_value FROM sys_dict_data WHERE dict_type='edu_material_type' AND dict_value=? AND status='0'",str(body,"materialType")).isEmpty(),"请选择有效资料类型");
            validateImage(str(merged,"coverUrl"),true);
            money(merged.get("price"));money(merged.get("shippingFee"));integer(merged.get("purchaseLimit"),1,99,"限购数量");
            if(merged.get("dispatchDays")!=null&&!str(merged,"dispatchDays").isBlank())integer(merged.get("dispatchDays"),0,365,"发货天数");
            if(editing) {
                require(body.get("version")!=null&&integer(body.get("version"),0,Integer.MAX_VALUE,"版本")==integer(old.get("version"),0,Integer.MAX_VALUE,"版本"),"商品已被更新，请刷新后重试");
                if(!str(old,"deliveryType").equals(str(merged,"deliveryType"))) {
                    require(db.rows("SELECT i.order_id FROM edu_material_order_item i JOIN edu_material_order o USING(order_id) WHERE i.material_id=? AND o.pay_status IN('1','2') LIMIT 1",material).isEmpty(),"有成交的商品不能修改交付方式");
                    require(db.rows("SELECT order_id FROM edu_material_order WHERE material_id=? AND order_status='WAIT_PAY' LIMIT 1",material).isEmpty(),"存在待支付订单，不能修改交付方式");
                }
                require(!body.containsKey("stockQuantity")||integer(body.get("stockQuantity"),0,Integer.MAX_VALUE,"库存")==integer(old.get("stockQuantity"),0,Integer.MAX_VALUE,"库存"),"请通过库存调整接口修改库存");
            }
            if(!str(merged,"filePath").isBlank()&&(!editing||!str(old,"filePath").equals(str(merged,"filePath")))) {
                storage.validateKey(str(merged,"filePath"),user,!teacher);
                try{values.put("file_size",java.nio.file.Files.size(storage.resolve(str(merged,"filePath"))));}
                catch(java.io.IOException e){throw new ServiceException("PDF文件无法读取");}
                require(!str(merged,"fileName").isBlank()&&str(merged,"fileName").length()<=200&&str(merged,"fileName").toLowerCase(Locale.ROOT).endsWith(".pdf"),"请填写有效PDF名称");
            }
            if("DIGITAL".equals(merged.get("deliveryType"))) {
                values.put("purchase_limit",1);values.put("shipping_fee",0);values.put("stock_quantity",0);values.put("stock_locked",0);merged.put("stockQuantity",0);merged.put("purchaseLimit",1);
            } else {
                values.put("file_path",null);values.put("file_name",null);values.put("file_size",null);
                if(!editing){values.put("stock_quantity",integer(body.getOrDefault("stockQuantity",0),0,Integer.MAX_VALUE,"库存"));merged.put("stockQuantity",values.get("stock_quantity"));}
            }
            if("1".equals(merged.get("shelfStatus")))validateShelf(merged);
            values.put("subject_name",str(merged,"subjectCode"));values.put("grade_name",str(merged,"gradeCode"));
            if(editing){values.put("version",integer(old.get("version"),0,Integer.MAX_VALUE,"版本")+1);values.put("update_time",now());values.put("update_by",String.valueOf(user));db.update("edu_material","material_id",material,values);}
            else {values.put("material_code",code("M"));values.put("upload_user_id",user);values.put("status","0");values.put("del_flag","0");values.put("create_time",now());values.put("create_by",String.valueOf(user));material=db.insert("edu_material",values);}
            if(body.containsKey("images")) {
                require(body.get("images") instanceof List<?>,"图片格式无效");List<?> images=(List<?>)body.get("images");require(images.size()<=25,"最多25张图片");int gallery=0;
                db.jdbc().update("DELETE FROM edu_material_image WHERE material_id=?",material);
                for(Object raw:images) {
                    require(raw instanceof Map<?,?>,"图片格式无效");Map<?,?> image=(Map<?,?>)raw;
                    String type=text(image.get("imageType"));require(List.of("GALLERY","DETAIL").contains(type),"图片类型无效");
                    if("GALLERY".equals(type))require(++gallery<=4,"封面加轮播图最多5张");
                    String url=text(image.get("imageUrl"));validateImage(url,false);
                    db.insert("edu_material_image",Map.of("material_id",material,"image_type",type,"image_url",url,"sort_order",integer(image.containsKey("sortOrder")?image.get("sortOrder"):0,0,1000,"图片排序"),"create_time",now()));
                }
            }
            return product(material,user,true);
        });
    }
    private static void validateImage(String url,boolean emptyAllowed) {
        require((emptyAllowed&&url.isBlank())||(url.length()<=500&&(url.startsWith("/profile/")||url.startsWith("https://")||url.startsWith("http://"))&&url.split("[?#]",2)[0].toLowerCase(Locale.ROOT).matches(".*\\.(png|jpg|jpeg)$")),"图片仅支持JPG/PNG公开路径");
    }
    private void validateShelf(Map<String,Object> p) {
        for(String key:List.of("title","coverUrl","detailText","subjectCode","gradeCode"))require(!str(p,key).isBlank(),"上架需填写名称、封面、详情、学科和年级");
        for(var entry:Map.of("subjectCode","edu_subject","gradeCode","edu_grade").entrySet())require(!db.rows("SELECT dict_value FROM sys_dict_data WHERE dict_type=? AND dict_value=? AND status='0'",entry.getValue(),p.get(entry.getKey())).isEmpty(),"请选择有效学科和年级");
        require(money(p.get("price")).signum()>0||"1".equals(str(p,"legacyFree")),"新商品上架价格必须大于0");
        if("PHYSICAL".equals(p.get("deliveryType")))require(integer(p.getOrDefault("stockQuantity",0),0,Integer.MAX_VALUE,"库存")-integer(p.getOrDefault("stockLocked",0),0,Integer.MAX_VALUE,"预占")>0,"上架需有可售库存");
        else {require(!str(p,"filePath").isBlank(),"电子商品上架需私有PDF");storage.resolve(str(p,"filePath"));}
    }
    public Map<String,Object> shelf(long user,long material,String status) {
        var body=new LinkedHashMap<String,Object>();body.put("materialId",String.valueOf(material));body.put("shelfStatus",status);
        body.put("version",db.one("SELECT version FROM edu_material WHERE material_id=?",material).get("version"));return saveProduct(user,body,false);
    }
    public Map<String,Object> stock(long user,long material,Map<String,Object> body) {
        int delta=integer(body.get("delta"),-100000000,100000000,"库存增减");require(delta!=0,"库存增减不能为0");require(!str(body,"remark").isBlank()&&str(body,"remark").length()<=500,"请填写库存调整原因");
        return transaction(()->{
            var p=db.one("SELECT * FROM edu_material WHERE material_id=? AND del_flag='0' FOR UPDATE",material);require("PHYSICAL".equals(p.get("deliveryType")),"电子商品不调整库存");
            require(db.jdbc().update("UPDATE edu_material SET stock_quantity=stock_quantity+?,version=version+1,update_by=?,update_time=? WHERE material_id=? AND stock_quantity+?>=stock_locked AND stock_quantity+?<=2147483647",delta,user,now(),material,delta,delta)==1,"不能低于预占库存或超出范围");
            return product(material,user,true);
        });
    }
    public void deleteProduct(long user,long material,boolean teacher) {
        transaction(()->{var p=db.one("SELECT * FROM edu_material WHERE material_id=? AND del_flag='0' FOR UPDATE",material);
            if(teacher)require(str(p,"uploadUserId").equals(String.valueOf(user))&&"2".equals(p.get("shelfStatus")),"只能删除自己提交的草稿");
            db.jdbc().update("UPDATE edu_material SET del_flag='2',shelf_status='0',version=version+1,update_by=?,update_time=? WHERE material_id=?",user,now(),material);return null;});
    }
    public List<Map<String,Object>> addresses(long user) {
        return db.rows("SELECT * FROM edu_user_address WHERE user_id=? AND del_flag='0' ORDER BY is_default DESC,address_id DESC",user).stream().map(a->{
            a.keySet().retainAll(new HashSet<>(List.of("addressId","userId","receiverName","receiverPhone","provinceCode","cityCode","districtCode","provinceName","cityName","districtName","detailAddress","postalCode","isDefault")));return a;
        }).toList();
    }
    public Map<String,Object> saveAddress(long user,Long address,Map<String,Object> body) {
        validateAddress(body);
        return transaction(()->{
            userLock(user);if(address!=null)db.one("SELECT address_id FROM edu_user_address WHERE address_id=? AND user_id=? AND del_flag='0' FOR UPDATE",address,user);
            Map<String,Object> values=fields(body,ADDRESS);for(String field:ADDRESS)if(values.containsKey(field))values.put(field,text(values.get(field)));
            int def=integer(body.getOrDefault("isDefault",0),0,1,"默认地址");
            if(def==1)db.jdbc().update("UPDATE edu_user_address SET is_default=0 WHERE user_id=? AND del_flag='0'",user);
            values.put("is_default",def);long saved;
            if(address==null){values.put("user_id",user);values.put("create_time",now());saved=db.insert("edu_user_address",values);}
            else {values.put("update_time",now());db.update("edu_user_address","address_id",address,values);saved=address;}
            return addresses(user).stream().filter(a->a.get("addressId").equals(String.valueOf(saved))).findFirst().orElseThrow();
        });
    }
    public void deleteAddress(long user,long address) {transaction(()->{userLock(user);require(db.jdbc().update("UPDATE edu_user_address SET del_flag='2',is_default=0,update_time=? WHERE address_id=? AND user_id=? AND del_flag='0'",now(),address,user)==1,"地址不存在或无权访问");return null;});}
    public Map<String,Object> defaultAddress(long user,long address) {return transaction(()->{userLock(user);var a=db.one("SELECT * FROM edu_user_address WHERE address_id=? AND user_id=? AND del_flag='0'",address,user);a.put("isDefault",1);return saveAddress(user,address,a);});}

}
