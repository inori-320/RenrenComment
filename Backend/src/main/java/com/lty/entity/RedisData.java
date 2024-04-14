package com.lty.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author lty
 */
@Data
public class RedisData {
    private Object data;
    private LocalDateTime expireTime;
}
