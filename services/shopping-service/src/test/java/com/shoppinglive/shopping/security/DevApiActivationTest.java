package com.shoppinglive.shopping.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import com.shoppinglive.shopping.image.api.DevProductImageController;
import com.shoppinglive.shopping.image.application.DevImageResetService;
import com.shoppinglive.shopping.image.application.ImageStorage;
import com.shoppinglive.shopping.image.infrastructure.ProductImageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DevApiActivationTest {
    private ApplicationContextRunner context(String... profiles) {
        return new ApplicationContextRunner().withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
            .withUserConfiguration(DevProductImageController.class, DevImageResetService.class)
            .withBean(ProductImageRepository.class, () -> mock(ProductImageRepository.class))
            .withBean(ImageStorage.class, () -> mock(ImageStorage.class));
    }

    @Test
    void localOrTestAlsoRequiresExplicitFlagAndDevCannotOverrideIt() {
        for (String profile : new String[] {"local", "test"}) {
            context(profile).run(context -> assertThat(context).doesNotHaveBean(DevProductImageController.class));
            context(profile).withPropertyValues("shopping.dev-api.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DevProductImageController.class));
        }
        for (String[] profiles : new String[][] {{"dev"}, {"prod"}, {"local", "dev"}, {"test", "prod"}}) {
            context(profiles).withPropertyValues("shopping.dev-api.enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DevProductImageController.class)
                    .doesNotHaveBean(DevImageResetService.class));
        }
    }
}
