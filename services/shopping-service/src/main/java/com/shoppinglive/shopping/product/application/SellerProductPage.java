package com.shoppinglive.shopping.product.application;

import java.util.List;

public record SellerProductPage(List<ProductSnapshot> items, int page, int size,
                                long totalElements, int totalPages) { }
