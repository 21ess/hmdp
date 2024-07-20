package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP = 1704067200L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyPrefix) {
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long timeStamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;

        // 2.序列号，子增长，拼接时间，保证序列号不溢出(32bit)
        // 2.1获取当前日期
        String today = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Long increment = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + today);


        // 3.拼接
        return timeStamp << 32 | increment;

    }

//    public static void main(String[] args) {
//        LocalDateTime localDateTime = LocalDateTime.of(2024, 1, 1, 0, 0, 0);
//        System.out.println(localDateTime.toEpochSecond(ZoneOffset.UTC));
//    }
}
