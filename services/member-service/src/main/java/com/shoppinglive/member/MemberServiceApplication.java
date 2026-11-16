package com.shoppinglive.member;

import com.shoppinglive.common.local.LocalApplication;
import com.shoppinglive.common.persistence.JpaAuditingConfig;
import com.shoppinglive.common.security.AuthenticationConfiguration;
import com.shoppinglive.common.web.WebConfiguration;
import com.shoppinglive.member.operations.SellerBootstrapCommand;
import java.util.Arrays;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({JpaAuditingConfig.class, WebConfiguration.class, AuthenticationConfiguration.class})
public class MemberServiceApplication {

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--bootstrap-seller")) {
            if (args.length != 1) {
                throw new IllegalArgumentException(
                        "--bootstrap-seller 인자만 허용합니다. 판매자 정보는 환경변수로 지정해 주세요.");
            }
            try (var context = SellerBootstrapCommand.openNonWebContext()) {
                var memberId =
                        context.getBean(SellerBootstrapCommand.class).execute(System.getenv());
                System.out.println("판매자 계정 생성 완료: 회원 ID=" + memberId);
            }
            return;
        }
        LocalApplication.run(MemberServiceApplication.class, args);
    }
}
