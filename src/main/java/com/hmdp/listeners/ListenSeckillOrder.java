package com.hmdp.listeners;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

@Component
@Slf4j
public class ListenSeckillOrder {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @RabbitListener(queues = SystemConstants.SECKILL_VOUCHER_SAVE_QUEUE)
    public void asyncSaveOrder(VoucherOrder voucherOrder) {
        log.info("订单信息为{}", JSONUtil.parse(voucherOrder));
        createVoucherOrder(voucherOrder);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 2.1 判断一人一单
        Long voucherId = voucherOrder.getVoucherId();
        Integer count = voucherOrderService.query().eq("user_id", voucherOrder.getUserId()).eq("voucher_id",
                voucherId).count();
        if (count > 0) {
            log.error("不能重复下单");
            return;
        }

        // 3.减少库存，CAS方案，判断当前的库存是否是之前查询到库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }

        // 4.创建订单
        voucherOrderService.save(voucherOrder);
    }
}
