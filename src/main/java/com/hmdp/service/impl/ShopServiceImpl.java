package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author yuhao
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        // Shop shop = queryWithoutPassThrough(id);
        // Shop shop = queryWithMutex(id);
        Shop shop = queryWithLogicExpire(id);
        if (shop == null) return  Result.fail("店铺不存在");

        return Result.ok(shop);
    }

    /**
     * 解决热点数据的缓存击穿问题
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        if (id < 0) return null;
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        if (StrUtil.isNotBlank(shopJson)) { // 非空直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断是否是数据库不存在, 我们默认设置为""
        if (shopJson != null) {return null;}

        // 缓存重建
        String lockKey = "lock:shop:" + id; // 相当于对每个shop加锁
        // 1.获取互斥锁
        Shop shop = null;
        try {
            boolean flag = tryLock(lockKey);
            // 2.失败，休眠，双检策略
            if (!flag) {
                String shopRe;
                while (true) {
                    TimeUnit.MILLISECONDS.sleep(50);
                    shopRe = stringRedisTemplate.opsForValue().get(shopKey);
                    if (shopRe != null) break;
                }
                return JSONUtil.toBean(shopRe, Shop.class);
            }
            // 3.成功获取锁，读mysql，写redis，释放互斥锁
            shop = getById(id);
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "",
                        RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                log.debug("店铺不存在");
                return null;
            }
            stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop),
                    RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);    // 防止死锁
        }
        return shop;
    }

    /**
     * 解决缓存穿透
     * 利用null
     * @param id
     * @return
     */
    public Shop queryWithoutPassThrough(Long id) {
        if (id <= 0) {
            return null;
        }

        // 1.查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);

        // 2.存在，直接返回即可
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 2.1 判断当前查询到value是否是一个空字符串
        if (shopJson != null) {
            return null;
        }

        // 3.不存在，查询数据库
        Shop shop = getById(id);
        if (shop == null) {
            // 如果数据库没有商户，将当前的key设置为空字符串，防止缓存穿透
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "",
                    RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            log.debug("店铺不存在");
            return null;
        }

        // 4.回写redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + shop.getId(),
                JSONUtil.toJsonStr(shop));

        // 5.设置过期时间
        stringRedisTemplate.expire(RedisConstants.CACHE_SHOP_KEY + shop.getId(), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }

    private static final ThreadPoolExecutor CACHE_REBUILD_EXECUTOR = new ThreadPoolExecutor(
            2,
            5,
            2L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(3)
            );

    /**
     * 使用逻辑过期来解决缓存击穿问题
     * @param id
     * @return
     */
    public Shop queryWithLogicExpire(Long id) {
        // 1.默认必然能查询到数据，缓存预热，反序列化Shop信息
        if (id < 0) return null;
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        if (StrUtil.isBlank(shopJson)) {
            // 数据空直接返回
            return null;
        }
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 2.判断数据是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 3.未过期直接返回
            return shop;
        }

        // 4.过期，缓存重建
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean lock = tryLock(lockKey);
        if (!lock) {
            // 4.1获取互斥锁，如果失败直接返回商户信息
            return shop;
        }

        // 4.2成功，启动异步线程，
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            // 重建
            try {
                this.saveShop2Redis(id, 20L);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unlock(lockKey);
            }
        });

        return shop;
    }

    public void saveShop2Redis(Long id, Long expireSecond) {
        // 1.查询商户
        Shop shop = getById(id);
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        // 2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSecond));
        // 3.写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,
                JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    // 更新数据，再删除缓存
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            Result.fail("店铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
