package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.*;

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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTask = new ArrayBlockingQueue<>(1024 * 1024);
    private static final Executor SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @Override
    public Result seckillVouchers(Long voucherId) {
        // 1.执行lua脚本
        // 1.1 需要参数
        Long userId = UserHolder.getUser().getId();
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );

        // 2.判断lua脚本的结果
        // 3.1 !=0返回异常信息
        int flag = result.intValue();
        if (flag != 0) {
            return Result.fail(flag == 1 ? "库存不足" : "不能重复下单");
        }

        // 3.2 ==0保存订单信息到阻塞队列
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder
                .setVoucherId(voucherId)
                .setUserId(userId)
                .setId(orderId);
        orderTask.add(voucherOrder);
        // 抢单完成

        // TODO:4.开启异步任务
        // 4.1

        return Result.ok(orderId);
    }

//    @Override
//    public Result seckillVouchers(Long voucherId) {
//        // 1.查询秒杀卷
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        // 2.判断时间以及库存
//        LocalDateTime beginTime = voucher.getBeginTime();
//        LocalDateTime endTime = voucher.getEndTime();
//        LocalDateTime now = LocalDateTime.now();
//        if (beginTime.isAfter(now)) {
//            Result.fail("秒杀未开始");
//        }
//        if (endTime.isBefore(now)) {
//            Result.fail("秒杀已结束");
//        }
//        if (voucher.getStock() <= 0) {
//            Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//        String orderLockKey = "lock:order:" + userId;
//
//        RLock lock = redissonClient.getLock(orderLockKey);
//        boolean isLock = lock.tryLock();
//        if (!isLock) {
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            lock.unlock();
//        }
//    }

    private Result withSimpleLock(Long voucherId, String orderLockKey) {
        SimpleRedisLock redisLock = new SimpleRedisLock(orderLockKey, stringRedisTemplate);
        boolean isLock = redisLock.tryLock(5L);
        if (!isLock) {
            return Result.fail("不允许重复下单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            redisLock.unlock();
        }
    }

    private static Result syncLock(Long voucherId) {
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
