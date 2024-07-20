package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Override

    public Result seckillVouchers(Long voucherId) {
        // 1.查询秒杀卷
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 2.判断时间以及库存
        LocalDateTime beginTime = voucher.getBeginTime();
        LocalDateTime endTime = voucher.getEndTime();
        LocalDateTime now = LocalDateTime.now();
        if (beginTime.isAfter(now)) {
            Result.fail("秒杀未开始");
        }
        if (endTime.isBefore(now)) {
            Result.fail("秒杀已结束");
        }
        if (voucher.getStock() <= 0) {
            Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        // 注意锁释放和事务提交的先后顺序
        synchronized (userId.toString().intern()) {// 先将id转化为字符串，在从字符串常量池中查找，从而保证对象锁的正确
            // 获取代理对象
            // 1.如果直接调用方法，spring的事务会失效，因为spring是通过代理来实现事务的
            // 2.需要先获得当前的代理对象再通过代理对象来调用方法才能实现事务
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 2.1 判断一人一单
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            Result.fail("用户已经购买了一次");
        }

        // 3.减少库存，CAS方案，判断当前的库存是否是之前查询到库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }


        // 4.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();

        voucherOrder
                .setId(redisIdWorker.nextId("order"))
                .setUserId(userId)
                .setVoucherId(voucherId);
        voucherOrderService.save(voucherOrder);
        return Result.ok(voucherOrder.getId());
    }
}
