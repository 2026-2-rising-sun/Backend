package com.shoppinglive.shopping.product.dto;

import com.shoppinglive.shopping.product.entity.ProductCategory;
import com.shoppinglive.shopping.product.service.ProductRegistrationService;

/** 상품 기본정보 등록 입력. 검증·정규화는 {@link ProductRegistrationService} 가 한다. */
public record RegisterProductCommand(
        String name, String description, Long mainImageId, ProductCategory category) {}
