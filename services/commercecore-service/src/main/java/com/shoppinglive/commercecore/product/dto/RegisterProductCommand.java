package com.shoppinglive.commercecore.product.dto;

import com.shoppinglive.commercecore.product.entity.ProductCategory;
import com.shoppinglive.commercecore.product.service.ProductRegistrationService;

/** 상품 기본정보 등록 입력. 검증·정규화는 {@link ProductRegistrationService} 가 한다. */
public record RegisterProductCommand(
        String name, String description, Long mainImageId, ProductCategory category) {}
