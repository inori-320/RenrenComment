package com.lty.service.impl;

import com.lty.dto.Result;
import com.lty.entity.SeckillVoucher;
import com.lty.entity.VoucherOrder;
import com.lty.mapper.VoucherOrderMapper;
import com.lty.service.ISeckillVoucherService;
import com.lty.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lty.utils.RedisIdWorker;
import com.lty.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author lty
 * @since 2024-4-15
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;

    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        // 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 判断秒杀是否开始和结束
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())
                || voucher.getEndTime().isBefore(LocalDateTime.now())){
            // 尚未开始
            return Result.fail("秒杀尚未开始或已结束！！");
        }
        // 判断库存是否充足
        if (voucher.getStock() < 1){
            return Result.fail("抱歉，该优惠券已抢光！");
        }
        // 扣减库存，并使用CAS乐观锁判断线程冲突情况
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if(!success){
            return Result.fail("抱歉，该优惠券已抢光！");
        }
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderId);
    }
}
