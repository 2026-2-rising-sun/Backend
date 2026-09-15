package com.shoppinglive.common.resilience;

/**
 * Names of the shared Resilience4j base configs declared in
 * {@code classpath:shoppinglive-resilience-defaults.yml}. A service imports that file via
 * {@code spring.config.import} and then only has to name its instances.
 */
public final class ResilienceDefaults {

    public static final String BASE_CONFIG = "shoppinglive-default";

    private ResilienceDefaults() {
    }
}
