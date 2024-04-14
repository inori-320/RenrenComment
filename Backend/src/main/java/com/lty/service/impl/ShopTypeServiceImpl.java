package com.lty.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.lty.dto.Result;
import com.lty.entity.ShopType;
import com.lty.mapper.ShopTypeMapper;
import com.lty.service.IShopTypeService;
import static com.lty.utils.RedisConstants.*;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public List<ShopType> getList() {
        // 先检查redis中是否有数据
        String data = stringRedisTemplate.opsForValue().get(CACHE_TYPE_KEY);
        if(StrUtil.isNotBlank(data)){
            return JSONUtil.toList(data, ShopType.class);
        }
        // 否则，从数据库中查数据并存到redis中
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        stringRedisTemplate.opsForValue().set(CACHE_TYPE_KEY, JSONUtil.toJsonStr(shopTypes));
        return shopTypes;
    }
}
