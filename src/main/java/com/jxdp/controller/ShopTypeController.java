package com.jxdp.controller;


import com.jxdp.dto.Result;
import com.jxdp.service.IShopTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    /**
     * 查询商铺类型列表
     * @return
     */
    @GetMapping("list")
    public Result queryTypeList() {
        Result result = typeService.queryTypeList();
        return result;
    }
}
