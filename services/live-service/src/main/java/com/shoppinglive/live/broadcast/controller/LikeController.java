package com.shoppinglive.live.broadcast.controller;

import com.shoppinglive.common.core.ApiResponse;
import com.shoppinglive.common.security.AuthenticatedUser;
import com.shoppinglive.live.broadcast.dto.LikeInput;
import com.shoppinglive.live.broadcast.dto.LikeTotalResponse;
import com.shoppinglive.live.broadcast.dto.MemberLikeResponse;
import com.shoppinglive.live.broadcast.service.LikeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/broadcasts/{id}/likes")
public class LikeController {
    private final LikeService likes;

    public LikeController(LikeService likes) {
        this.likes = likes;
    }

    @Operation(
            summary = "좋아요 합계 조회 (#148)",
            description =
                    "로그인 없이 PostgreSQL에 커밋된 회원별 좋아요 합계와 version을 조회한다.\n좋아요가 없는 방송은 total=0, version=0이다. 종료된 방송도 조회한다.\n취소로 total은 감소할 수 있으므로 클라이언트는 더 큰 version의 합계를 반영한다.")
    @GetMapping
    public ApiResponse<LikeTotalResponse> total(@PathVariable long id) {
        return ApiResponse.ok(likes.total(id));
    }

    @Operation(
            summary = "내 좋아요 상태 조회 (#148)",
            description =
                    "인증 회원의 liked, stateVersion과 방송 total, version을 함께 조회한다.\n참여 전 상태는 liked=false, stateVersion=0이다. 종료된 방송도 조회한다.\nstateVersion과 방송 version은 별도로 비교한다. 다른 회원의 변경은 내 stateVersion을 바꾸지 않는다.")
    @SecurityRequirement(name = "BearerAuth")
    @GetMapping("/mine")
    public ApiResponse<MemberLikeResponse> mine(
            @PathVariable long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.ok(likes.mine(id, user.memberId()));
    }

    @Operation(
            summary = "내 좋아요 설정 또는 취소 (#148)",
            description =
                    "LIVE 방송에 회원당 하나의 좋아요를 설정하거나 취소한다. 현재 상태와 같으면 합계와 version은 바뀌지 않는다.\nIdempotency-Key는 UUID이고 방송·회원 범위다. 새 조작은 새 키, 동일 요청의 재시도는 같은 키를 쓴다.\n같은 키와 같은 liked는 최초 응답을 재생한다. 다른 liked는 409이다.\n요청 결과는 방송 수명 동안 만료시키지 않는다. 종료 이후에도 기존 요청은 재생하고 신규 요청은 409이다.\n응답 유실·503은 같은 키로 재시도한다. 재생 응답이 오래되었으면 stateVersion과 version을 각각 비교한다.\n이전 POST 누적 API는 지원하지 않는다. local/dev 전환 시 기존 익명 누적값은 새 회원 합계에 포함하지 않는다.")
    @SecurityRequirement(name = "BearerAuth")
    @PutMapping("/mine")
    public ApiResponse<MemberLikeResponse> set(
            @PathVariable long id,
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody LikeInput input) {
        return ApiResponse.ok(likes.set(id, user.memberId(), key, input.liked()));
    }
}
