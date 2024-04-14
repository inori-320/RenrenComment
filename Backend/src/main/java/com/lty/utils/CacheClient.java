package com.lty.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lty.entity.RedisData;
import com.lty.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.lty.utils.RedisConstants.*;

/**
 * @author lty
 */
@Component
@Slf4j

public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    private Random random = new Random();
    private static final ExecutorService CACHE_REBUILD_POOL = Executors.newFixedThreadPool(10);


    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private boolean tryLock(String key){
        Boolean lock = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOCK_SHOP_KEY + key, "1", LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(lock);
    }

    private void unLock(String key){
        stringRedisTemplate.delete(LOCK_SHOP_KEY + key);
    }

    /**
     * 在redis中创建string类型的键值对，并且设置过期时间
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        String json = JSONUtil.toJsonStr(value);
        stringRedisTemplate.opsForValue().set(key, json, time + random.nextLong(5), unit);
    }

    /**
     * 在redis中创建string类型的键值对，并且配置逻辑过期
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        String json = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(key,json);
    }

    /**
     * 根据指定的key查询缓存，斌七个反序列化为指定类型，利用缓存空值解决缓存穿透问题
     * @param keyPre
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <T>
     * @param <ID>
     */
    public <T, ID> T queryWithPassThrough(
            String keyPre, ID id, Class<T> type, Function<ID, T> dbFallback, Long time, TimeUnit unit){
        String key = keyPre + id;
        // 从redis中查询店铺是否存在
        String json = stringRedisTemplate.opsForValue().get(key);
        // 存在则直接返回
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        if(json != null){
            // 如果为空值，就直接返回
            return null;
        }
        // 不存在就从数据库中查数据，再存到redis中
        T t = dbFallback.apply(id);
        if(t == null){
            // 防止缓存穿透，在redis中添加一条空数据
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 防止缓存雪崩，在持续时间出添加随机数
        this.set(key, t, time, unit);
        return t;
    }

    /**
     * 基于逻辑过期解决缓冲击穿问题
     * @param id
     * @return
     */
    public <T, ID> T queryWithLogicalExpire(
            String keyPre, ID id, Class<T> type, Function<ID, T> dbFallback, Long time, TimeUnit unit){
        String key = keyPre + id;
        // 从redis中查询店铺是否存在
        String json = stringRedisTemplate.opsForValue().get(key);
        // 不存在直接返回空
        if(StrUtil.isBlank(json)){
            return null;
        }
        // 先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        T t = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断缓存是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            return t;
        }
        // 过期了，需要缓存重建，获取锁
        boolean lock = tryLock(id.toString());
        if(lock){
            // 首先进行二次检测，判断缓存是否过期，如果没过期直接返回
            json = stringRedisTemplate.opsForValue().get(key);
            redisData = JSONUtil.toBean(json, RedisData.class);
            t = JSONUtil.toBean((JSONObject) redisData.getData(), type);
            expireTime = redisData.getExpireTime();
            if(expireTime.isAfter(LocalDateTime.now())){
                return t;
            }
            // 开启独立线程，实现缓存重建
            CACHE_REBUILD_POOL.submit(() -> {
                try {
                    T t1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, t1, time, unit);
                } catch (Exception e){
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unLock(id.toString());
                }
            });
        }
        // 返回过期的商铺信息
        return t;
    }

    /**
     * 基于互斥锁解决缓存击穿问题
     * @param id
     * @return
     */
    public <T, ID> T queryWithMutex(
            String keyPre, ID id, Class<T> type, Function<ID, T> dbFallback, Long time, TimeUnit unit){
        String key = keyPre + id;
        // 从redis中查询店铺是否存在
        String json = stringRedisTemplate.opsForValue().get(key);
        // 存在则直接返回
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        if(json != null){
            // 如果为空值，就直接返回
            return null;
        }
        // 获取互斥锁
        T t = null;
        try {
            boolean lock = tryLock(id.toString());
            // 如果锁被占用，就休眠重试
            while (!lock) {
                Thread.sleep(50);
                return queryWithMutex(keyPre, id, type, dbFallback, time, unit);
            }
            // 二次检查，可能会有其他线程已经修改了缓存
            json = stringRedisTemplate.opsForValue().get(key);
            // 存在则直接返回
            if(StrUtil.isNotBlank(json)){
                return JSONUtil.toBean(json, type);
            }
            if(json != null){
                // 如果为空值，就直接返回
                return null;
            }
            // 从数据库中查数据，再存到redis中
            t = dbFallback.apply(id);
            if (t == null) {
                // 防止缓存穿透，在redis中添加一条空数据
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 防止缓存雪崩，在持续时间出添加随机数
            stringRedisTemplate.opsForValue().set(key,
                    JSONUtil.toJsonStr(t),
                    time + random.nextLong(5),
                    unit);
        } catch (Exception e){
            throw new RuntimeException(e);
        } finally {
            unLock(id.toString());
        }
        return t;
    }
}
