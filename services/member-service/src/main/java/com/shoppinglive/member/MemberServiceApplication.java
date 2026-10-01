package com.shoppinglive.member;

import com.shoppinglive.common.persistence.JpaAuditingConfig;
import com.shoppinglive.member.operations.AdminBootstrapCommand;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(JpaAuditingConfig.class)
public class MemberServiceApplication {

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--bootstrap-admin")) {
            if (args.length != 1) throw new IllegalArgumentException("Admin bootstrap accepts only --bootstrap-admin; use environment variables");
            try (var context = AdminBootstrapCommand.openNonWebContext()) {
                var memberId = context.getBean(AdminBootstrapCommand.class).execute(System.getenv());
                System.out.println("Created ADMIN member " + memberId);
            }
            return;
        }
        SpringApplication.run(MemberServiceApplication.class, args);
    }
}
