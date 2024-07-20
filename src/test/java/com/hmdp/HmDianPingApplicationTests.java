package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import javax.print.attribute.standard.MediaName;
import java.util.Arrays;
import java.util.Map;
import java.util.Scanner;

@SpringBootTest
class HmDianPingApplicationTests {
    @Resource
    private ShopServiceImpl shopService;

    @Test
    void shopServiceTest() {
        shopService.saveShop2Redis(1L, 10L);
    }

}
