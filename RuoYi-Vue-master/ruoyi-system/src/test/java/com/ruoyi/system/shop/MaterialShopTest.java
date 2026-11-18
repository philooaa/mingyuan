package com.ruoyi.system.shop;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialShopTest {
    @Test void productionProfileStillRejectsMockPaymentEvenWithLocalOptIn() {
        var env=new org.springframework.core.env.StandardEnvironment();
        env.setActiveProfiles("local","production");
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test-payment-mode",java.util.Map.of("payment.mode","mock")));
        var service=new ShopService(null,null,env);
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->service.pay(2,1));
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->service.refund(1,1,"模拟"));
    }
    @Test void legacyBuyPublishGenericOrderEditAndDynamicDownloadAreDisabled() {
        var legacy=new com.ruoyi.system.service.impl.MiniAppMaterialServiceImpl();
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->legacy.buyMaterial(new com.ruoyi.common.core.domain.model.MiniAppMaterialBuyBody()));
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->legacy.uploadMaterial(new com.ruoyi.common.core.domain.model.MiniAppMaterialUploadBody()));
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->legacy.updateMyMaterial(new com.ruoyi.system.domain.EduMaterial()));
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->legacy.selectDownloadableOrder(1L));
        var orders=new com.ruoyi.system.service.impl.EduMaterialOrderServiceImpl();
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->orders.updateEduMaterialOrder(new com.ruoyi.system.domain.EduMaterialOrder()));
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->orders.insertEduMaterialOrder(new com.ruoyi.system.domain.EduMaterialOrder()));
        assertThrows(com.ruoyi.common.exception.ServiceException.class,()->orders.deleteEduMaterialOrderByOrderId(1L));
        assertNotEquals(200,new com.ruoyi.system.controller.MiniAppMaterialController(null,null,null).disabledBuy().get("code"));
        assertNotEquals(200,new com.ruoyi.system.controller.EduMaterialOrderController(null).disabledEdit().get("code"));
        assertNotEquals(200,new com.ruoyi.system.controller.EduMaterialOrderController(null).disabledDelete().get("code"));
    }
    @Test void publicAndCommonPdfDownloadsAreRejectedIncludingEncodedAndPrivateKeys() {
        assertTrue(ShopPdfGuard.forbidden("/profile/upload/legacy.pdf",null,null));
        assertTrue(ShopPdfGuard.forbidden("/profile/upload/legacy%252Epdf",null,null));
        assertTrue(ShopPdfGuard.forbidden("/common/download/resource","/profile/upload/legacy.pdf",null));
        assertTrue(ShopPdfGuard.forbidden("/common/download/resource","shop/3/012345.pdf",null));
        assertFalse(ShopPdfGuard.forbidden("/profile/upload/cover.png",null,null));
    }
}
