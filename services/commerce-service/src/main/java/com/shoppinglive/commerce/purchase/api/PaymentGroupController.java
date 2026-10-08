package com.shoppinglive.commerce.purchase.api;
import com.shoppinglive.commerce.purchase.application.PaymentGroupService;
import com.shoppinglive.commerce.purchase.application.PaymentGroupService.*;
import com.shoppinglive.commerce.payments.api.PaymentAttemptResponse;
import com.shoppinglive.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import com.shoppinglive.common.core.BusinessException;
import com.shoppinglive.common.core.ErrorCode;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
@RestController
public class PaymentGroupController {
 private final PaymentGroupService service;
 public PaymentGroupController(PaymentGroupService service){this.service=service;}
 public record PreviewRequest(@NotEmpty @Size(max=100) List<Selection> items){}
 public record CreateRequest(@NotEmpty @Size(max=100) List<Selection> items,
  @NotBlank @Size(max=64) String buyerName,@NotBlank @Pattern(regexp="^[0-9-]{9,32}$") String buyerPhone,@NotNull @Positive Long expectedTotalAmount){}
 @PostMapping("/v1/cart/checkout")
 public Quote quote(@AuthenticationPrincipal AuthenticatedUser member,@Valid @RequestBody PreviewRequest input){return service.preview(member.memberId(),input.items());}
 @PostMapping("/v1/cart/orders")
 public ResponseEntity<GroupResponse> create(@AuthenticationPrincipal AuthenticatedUser member,@RequestHeader("X-Idempotency-Key") String key,@Valid @RequestBody CreateRequest input){
  Creation c=service.create(member.memberId(),input.items(),input.buyerName(),input.buyerPhone(),input.expectedTotalAmount(),key);return ResponseEntity.status(c.created()?201:200).body(c.group());
 }
 @GetMapping("/v1/payment-groups/active")
 public ResponseEntity<GroupResponse> active(@AuthenticationPrincipal AuthenticatedUser member){return service.active(member.memberId()).map(ResponseEntity::ok).orElseGet(()->ResponseEntity.noContent().build());}
 @GetMapping("/v1/payment-groups/{number}")
 public GroupResponse get(@AuthenticationPrincipal AuthenticatedUser member,@PathVariable String number){return service.get(member.memberId(),number);}
 @PostMapping("/v1/payment-groups/{number}/payments")
 public ResponseEntity<PaymentAttemptResponse> pay(@AuthenticationPrincipal AuthenticatedUser member,@PathVariable String number,@RequestHeader("X-Idempotency-Key") String key,@RequestBody(required=false) Map<String,Object> input){if(input!=null && !input.isEmpty())throw new BusinessException(ErrorCode.INVALID_REQUEST,"결제 요청 본문은 비어 있어야 합니다.");return ResponseEntity.status(202).body(PaymentAttemptResponse.from(service.start(member.memberId(),number,key)));}
 @GetMapping("/v1/payment-groups/{number}/payments/{id}")
 public PaymentAttemptResponse payment(@AuthenticationPrincipal AuthenticatedUser member,@PathVariable String number,@PathVariable Long id){return PaymentAttemptResponse.from(service.payment(member.memberId(),number,id));}
 @PostMapping("/v1/payment-groups/{number}/cancel")
 public ResponseEntity<Void> cancel(@AuthenticationPrincipal AuthenticatedUser member,@PathVariable String number){service.cancel(member.memberId(),number);return ResponseEntity.noContent().build();}
}
