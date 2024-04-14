package com.lty.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lty.dto.Result;
import com.lty.entity.RedisData;
import com.lty.entity.Shop;
import com.lty.mapper.ShopMapper;
import com.lty.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lty.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveSetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.lty.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    private Random random = new Random();
    private static final ExecutorService CACHE_REBUILD_POOL = Executors.newFixedThreadPool(10);


    private boolean tryLock(String key){
        Boolean lock = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOCK_SHOP_KEY + key, "1", LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(lock);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(LOCK_SHOP_KEY + key);
    }

    private void saveDataToRedis(Long id, Long expireSeconds){
        // 从数据库中查询数据
        Shop shop = getById(id);
        // 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop));
    }

    @Override
    public Result queryById(Long id) {
        Shop shop = queryWithLogicalExpire(id);
        if(shop == null){
            return Result.fail("店铺不存在！");
        }
        return Result.ok(shop);
    }

    /**
     * 基于互斥锁解决缓存击穿问题
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 从redis中查询店铺是否存在
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 存在则直接返回
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if(shopJson != null){
            // 如果为空值，就直接返回
            return null;
        }
        // 获取互斥锁
        Shop shop = null;
        try {
            boolean lock = tryLock(id.toString());
            // 如果锁被占用，就休眠重试
            while (!lock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 二次检查，可能会有其他线程已经修改了缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);
            // 存在则直接返回
            if(StrUtil.isNotBlank(shopJson)){
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            if(shopJson != null){
                // 如果为空值，就直接返回
                return null;
            }
            // 从数据库中查数据，再存到redis中
            shop = getById(id);
            if (shop == null) {
                // 防止缓存穿透，在redis中添加一条空数据
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 防止缓存雪崩，在持续时间出添加随机数
            stringRedisTemplate.opsForValue().set(key,
                    JSONUtil.toJsonStr(shop),
                    CACHE_SHOP_TTL + random.nextLong(5),
                    TimeUnit.MINUTES);
        } catch (Exception e){
            throw new RuntimeException(e);
        } finally {
            unLock(id.toString());
        }
        return shop;
    }

    /**
     * 基于逻辑过期解决缓冲击穿问题
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 从redis中查询店铺是否存在
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 不存在直接返回空
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        // 先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断缓存是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        // 过期了，需要缓存重建，获取锁
        boolean lock = tryLock(id.toString());
        if(lock){
            // 首先进行二次检测，判断缓存是否过期，如果没过期直接返回
            shopJson = stringRedisTemplate.opsForValue().get(key);
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
            expireTime = redisData.getExpireTime();
            if(expireTime.isAfter(LocalDateTime.now())){
                return shop;
            }
            // 开启独立线程，实现缓存重建
            CACHE_REBUILD_POOL.submit(() -> {
                try {
                    saveDataToRedis(id, CACHE_VALID_TTL);
                } catch (Exception e){
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(id.toString());
                }
            });
        }
        // 返回过期的商铺信息
        return shop;
    }

    public Shop queryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        // 从redis中查询店铺是否存在
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 存在则直接返回
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if(shopJson != null){
            // 如果为空值，就直接返回
            return null;
        }
        // 不存在就从数据库中查数据，再存到redis中
        Shop shop = getById(id);
        if(shop == null){
            // 防止缓存穿透，在redis中添加一条空数据
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 防止缓存雪崩，在持续时间出添加随机数
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL + random.nextLong(5), TimeUnit.MINUTES);
        return shop;
    }

    @Transactional
    public Result updateShop(Shop shop) {
        if(shop.getId() == null){
            return Result.fail("店铺ID为空！");
        }
        // 先更新数据库
        updateById(shop);
        // 再删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
