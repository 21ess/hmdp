package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object obj, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(obj), time, unit);
    }

    public void setWithLogicExpire(String key, Object obj, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(obj);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(obj));
    }

    public <R, ID> R queryWithoutPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                             Long time, TimeUnit unit) {
        String key = keyPrefix + id;

        // 1.查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        // 2.存在，直接返回即可
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        // 2.1 判断当前查询到value是否是一个空字符串
        if (json != null) {
            return null;
        }

        // 3.不存在，查询数据库，交给用户实现一个Function类型
        R r = dbFallback.apply(id);
        if (r == null) {
            // 如果数据库没有商户，将当前的key设置为空字符串，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "",
                    RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        set(key, r, time, unit);
        return r;
    }

    private static final ThreadPoolExecutor CACHE_REBUILD_EXECUTOR = new ThreadPoolExecutor(
            2,
            5,
            2L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(3)
    );

    /**
     * 利用逻辑过期机制来防止缓存击穿
     * @param id
     * @return
     * @param <R>
     */
    public <R, ID> R queryWithLogicExpire(String keyPrefix, ID id, Class<R> type,
                                          Function<ID, R> dbFallback, Long logicTime, TimeUnit unit) {
        // 1.默认必然能查询到数据，缓存预热，反序列化Shop信息
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            // 数据空直接返回
            return null;
        }
        // 因为采用逻辑过期方法
        // redis中保存的数据为, 过期时间 + obj的json字符串，是RedisData的序列化对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 2.判断数据是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            // 3.未过期直接返回
            return r;
        }

        // 4.过期，缓存重建
        // 加锁：只要一个线程执行缓存重建即可, lock + 业务前缀 + id
        String lockKey = "lock:" + keyPrefix + id;
        boolean lock = tryLock(lockKey);
        if (!lock) {
            // 4.1获取互斥锁，如果失败直接返回商户信息
            return r;
        }

        // 4.2成功，启动异步线程，
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                // 4.3 先查询数据库
                R r1 = dbFallback.apply(id);
                // 4.4 再重建缓存
                this.setWithLogicExpire(key, r1, logicTime, unit);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                unlock(lockKey);
            }
        });
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 利用redis的分布式锁来解决缓存击穿问题
     * @param keyPrefix
     * @param id
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R, ID> R queryWithMutex(String keyPrefix,
                                    ID id,
                                    Class<R> type,
                                    Function<ID, R> dbFallback,
                                    Long time,
                                    TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) { // 非空直接返回
            return JSONUtil.toBean(json, type);
        }
        // 如果json==""说明，当前数据库不存在数据
        if (json != null) {return null;}

        // 缓存重建
        String lockKey = "lock:" + keyPrefix + id; // 相当于对每个shop加锁
        // 1.获取互斥锁
        try {
            boolean flag = tryLock(lockKey);
            // 2.失败，休眠，双检策略
            if (!flag) {
                while (true) {
                    TimeUnit.MILLISECONDS.sleep(50);
                    flag = tryLock(lockKey);    // 再次尝试获得锁
                    if (flag) {
                        String json1 = stringRedisTemplate.opsForValue().get(key);
                        if (StrUtil.isNotBlank(json1))
                            return JSONUtil.toBean(json1, type);   // 说明存在其他线程重建了缓存
                        if (json1 != null) return null;
                        break;
                    }
                }
            }
            // 3.成功获取锁，读mysql，写redis，释放互斥锁
            R r = dbFallback.apply(id);
            if (r == null) {
                // 数据库不存在，将value设置为""，防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, "",
                        RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                log.debug("店铺不存在");
                return null;
            }
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r), time, unit);
            return r;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);    // 防止死锁，
        }
    }
}
