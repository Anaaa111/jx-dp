package com.jxdp.service;

import com.jxdp.dto.Result;
import com.jxdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {
    /**
     * 秒杀下单
     * @param voucherId
     * @return
     */
    Result seckilloucher(Long voucherId);
    // Result createVoucherOrder (Long voucherId);

    /**
     * 用于异步下单的创建订单函数
     * @param voucherOrder
     * @return
     */
    void createVoucherOrder2 (VoucherOrder voucherOrder);
}
