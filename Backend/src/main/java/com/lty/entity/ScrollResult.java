package com.lty.entity;

import lombok.Data;

import java.util.List;

/**
 * @author lty
 */
@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
