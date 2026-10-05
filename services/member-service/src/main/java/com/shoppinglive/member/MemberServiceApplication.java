package com.shoppinglive.member;

import com.shoppinglive.common.persistence.JpaAuditingConfig;
import com.shoppinglive.member.operations.SellerBootstrapCommand;
import java.util.Arrays;
import com.shoppinglive.common.local.LocalApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(JpaAuditingConfig.class)
public class MemberServiceApplication {

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--bootstrap-seller")) {
            if (args.length != 1) throw new IllegalArgumentException("Seller bootstrap accepts only --bootstrap-seller; use environment variables");
            try (var context = SellerBootstrapCommand.openNonWebContext()) {
                var memberId = context.getBean(SellerBootstrapCommand.class).execute(System.getenv());
                System.out.println("Created SELLER member " + memberId);
            }
            return;
        }
        LocalApplication.run(MemberServiceApplication.class, args);
    }
}
