package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
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
}
