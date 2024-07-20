package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryTypeList() {
        String key = "shop:type:list";

        List<String> typeList = stringRedisTemplate.opsForList().range(key, 0, -1);
        if (!typeList.isEmpty()) {
            return typeList
                    .stream()
                    .map(o -> JSONUtil.toBean(o, ShopType.class))
                    .collect(Collectors.toList());
        }

        // 不存在，查询数据库
        List<ShopType> shopTypes = query().orderByAsc("sort").list();

        // 这里数据库必然存在，回写redis
        stringRedisTemplate.opsForList().rightPushAll(key,
                shopTypes
                        .stream()
                        .map(JSONUtil::toJsonStr)
                        .collect(Collectors.toList()));

        // 对于商户类型数据，采用内存淘汰策略
        return shopTypes;
    }
}
