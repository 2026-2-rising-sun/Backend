package com.shoppinglive.commerce.purchase.application;

import com.shoppinglive.commerce.cart.domain.CartItem;
import com.shoppinglive.commerce.cart.infrastructure.CartItemRepository;
import com.shoppinglive.commerce.orders.api.OrderResponse;
import com.shoppinglive.commerce.orders.application.OrderNumberGenerator;
import com.shoppinglive.commerce.orders.domain.*;
import com.shoppinglive.commerce.orders.infrastructure.OrderJpaRepository;
import com.shoppinglive.commerce.payments.api.PaymentAttemptResponse;
import com.shoppinglive.commerce.payments.application.*;
import com.shoppinglive.commerce.payments.domain.*;
import com.shoppinglive.commerce.payments.infrastructure.PaymentAttemptJpaRepository;
import com.shoppinglive.commerce.coupons.application.CouponPreviewService;
import com.shoppinglive.commerce.coupons.application.CouponReservationService;
import com.shoppinglive.commerce.purchase.domain.PaymentGroup;
import com.shoppinglive.commerce.purchase.infrastructure.PaymentGroupRepository;
import com.shoppinglive.commerce.sales.domain.*;
import com.shoppinglive.commerce.sales.infrastructure.*;
import com.shoppinglive.commerce.shopping.application.ShoppingClient;
import com.shoppinglive.common.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;

@Service
public class PaymentGroupService {
 public record Selection(Long itemId,Long version) {}
 public record Item(Long itemId,Long version,Long productId,String productName,Integer quantity,Long unitPrice,Long totalAmount,Long discountAmount,Long payableAmount) {}
 public record Quote(List<Item> items,Long totalAmount,String couponId,Long discountAmount,Long payableAmount) {}
 public record GroupResponse(String groupNumber,OrderStatus status,Long totalAmount,Long discountAmount,Long payableAmount,String couponId,Instant expiresAt,List<OrderResponse> orders,Long paymentId) {}
 public record Creation(GroupResponse group,boolean created) {}
 private static final List<OrderStatus> ACTIVE=List.of(OrderStatus.PENDING_PAYMENT,OrderStatus.PAYMENT_CONFIRMING);
 private final PaymentGroupRepository groups;
 private final OrderJpaRepository orders;
 private final CartItemRepository cart;
 private final SalesJpaRepository sales;
 private final SalesStockJpaRepository stock;
 private final PaymentAttemptJpaRepository payments;
 private final ShoppingClient shopping;
 private final PurchaseGuard guard;
 private final DurableMockGateway gateway;
 private final MockPaymentEngine engine;
 private final ObjectProvider<DevPaymentScenarioRegistry> scenarios;
 private final TransactionTemplate tx;
 private final JdbcTemplate jdbc;
 private final CouponPreviewService couponPreview;
 private final CouponReservationService couponReservations;
 private final ObjectMapper mapper;
 private final OrderNumberGenerator numbers;
 @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
 private final Duration expiration;
 public PaymentGroupService(PaymentGroupRepository groups,OrderJpaRepository orders,CartItemRepository cart,
   SalesJpaRepository sales,SalesStockJpaRepository stock,PaymentAttemptJpaRepository payments,ShoppingClient shopping,
   PurchaseGuard guard,DurableMockGateway gateway,MockPaymentEngine engine,ObjectProvider<DevPaymentScenarioRegistry> scenarios,
   TransactionTemplate tx,JdbcTemplate jdbc,ObjectMapper mapper,OrderNumberGenerator numbers,CouponPreviewService couponPreview,
   CouponReservationService couponReservations,
   @Value("${commerce.order.expiration.duration:PT15M}") Duration expiration){
  this.groups=groups;this.orders=orders;this.cart=cart;this.sales=sales;this.stock=stock;this.payments=payments;
  this.shopping=shopping;this.guard=guard;this.gateway=gateway;this.engine=engine;this.scenarios=scenarios;
  this.tx=tx;this.jdbc=jdbc;this.mapper=mapper;this.numbers=numbers;this.couponPreview=couponPreview;
  this.couponReservations=couponReservations;this.expiration=expiration;
 }
 public Quote preview(String member,List<Selection> selections){
  return preview(member,selections,null);
 }
 public Quote preview(String member,List<Selection> selections,String couponId){
  List<Selection> selected=validateSelections(selections);
  List<Item> items=new ArrayList<>();long total=0;
  for(Selection input:selected){
   CartItem c=cart.findByIdAndMemberId(input.itemId(),member).orElseThrow(()->error(ErrorCode.NOT_FOUND,"장바구니 항목을 찾을 수 없습니다."));
   if(!Objects.equals(c.getVersion(),input.version()))throw conflict("장바구니가 변경되었습니다. 다시 확인해 주세요.");
   String name=shopping.findProduct(c.getProductId()).orElseThrow(()->error(ErrorCode.NOT_FOUND,"상품을 찾을 수 없습니다.")).name();
   Sales s=sales.findByProductId(c.getProductId()).orElseThrow(()->error(ErrorCode.NOT_FOUND,"판매 정보를 찾을 수 없습니다."));
   SalesStock st=stock.findById(s.getId()).orElseThrow(()->error(ErrorCode.NOT_FOUND,"재고를 찾을 수 없습니다."));
   if(!s.getStatus().canAcceptNewOrder() || st.getAvailable()<c.getQuantity())throw conflict("구매할 수 없는 상품 또는 재고 부족입니다.");
   long amount=OrderAmounts.total(s.getPrice(),c.getQuantity());total=add(total,amount);
   items.add(new Item(c.getId(),c.getVersion(),c.getProductId(),name,c.getQuantity(),s.getPrice(),amount,0L,amount));
  }
  CouponPreviewService.Preview discount=couponPreview.preview(member,couponId,items.stream()
   .map(item->new CouponPreviewService.PricedItem("cart:"+item.itemId(),item.productId(),item.totalAmount())).toList());
  List<Item> priced=items.stream().map(item->{long itemDiscount=discount.itemDiscounts().get("cart:"+item.itemId());
   return new Item(item.itemId(),item.version(),item.productId(),item.productName(),item.quantity(),item.unitPrice(),item.totalAmount(),itemDiscount,item.totalAmount()-itemDiscount);
  }).toList();
  return new Quote(List.copyOf(priced),total,discount.couponId(),discount.discountAmount(),discount.payableAmount());
 }
 public Creation create(String member,List<Selection> selections,String buyer,String phone,long expected,String rawKey){
  return create(member,selections,buyer,phone,expected,null,rawKey);
 }
 public Creation create(String member,List<Selection> selections,String buyer,String phone,long expected,String couponId,String rawKey){
  validateKey(rawKey);String key="cart:"+fingerprint(rawKey).substring(0,59);List<Selection> selected=validateSelections(selections);
  String fingerprint=couponId==null?fingerprint(List.of(selected,buyer,phone,expected)):
   fingerprint(List.of(selected,buyer,phone,expected,couponId));
  Optional<PaymentGroup> existing=groups.findByMemberIdAndRequestKey(member,key);
  if(existing.isPresent())return replay(existing.get(),fingerprint);
  Quote quote=preview(member,selected,couponId);guard.ensure(member);
  return tx.execute(t->{
   guard.lock(member);
   Optional<PaymentGroup> replay=groups.findByMemberIdAndRequestKey(member,key);
   if(replay.isPresent())return replay(replay.get(),fingerprint);
   guard.requireNoActive(member);
   // Lock source rows first so a later success cannot delete intervening edits.
   for(Item item:quote.items()){
    CartItem current=cart.lockOwned(item.itemId(),member).orElseThrow(()->error(ErrorCode.NOT_FOUND,"장바구니 항목을 찾을 수 없습니다."));
    entityManager.refresh(current);
    if(!Objects.equals(current.getVersion(),item.version()))throw conflict("장바구니가 변경되었습니다.");
   }
   List<Sales> selectedSales=quote.items().stream().map(i->sales.findByProductId(i.productId()).orElseThrow(()->error(ErrorCode.NOT_FOUND,"판매 정보 없음")))
    .sorted(Comparator.comparing(Sales::getId)).toList();
   for(Sales s:selectedSales)guard.lockSales(s.getId());
   // Reload prices after acquiring database locks, including administrator changes.
   Map<Long,Sales> currentSales=new HashMap<>();long total=0;
   for(Item item:quote.items()){
    Sales s=sales.findByProductId(item.productId()).orElseThrow();
    // JDBC read avoids a pre-lock JPA snapshot.
    Long price=jdbc.queryForObject("SELECT price FROM sales_info WHERE id=?",Long.class,s.getId());
    String status=jdbc.queryForObject("SELECT status FROM sales_info WHERE id=?",String.class,s.getId());
    if(!SalesStatus.valueOf(status).canAcceptNewOrder())throw conflict("판매 중인 상품이 아닙니다.");
    if(!Objects.equals(price,item.unitPrice()))throw conflict("가격이 변경되었습니다. 주문서를 다시 확인해 주세요.");
    total=add(total,OrderAmounts.total(price,item.quantity()));currentSales.put(item.productId(),s);
   }
   if(total!=expected)throw conflict("주문 금액이 변경되었습니다. 다시 확인해 주세요.");
   couponReservations.reserve(member,couponId);
   PaymentGroup group=groups.saveAndFlush(new PaymentGroup("PG-"+UUID.randomUUID(),member,key,fingerprint,total,
    Instant.now().plus(expiration),quote.discountAmount(),couponId));
   for(Item item:quote.items()){
    Sales s=currentSales.get(item.productId());
    Order order=new Order(numbers.generate(),s.getId(),item.quantity(),item.unitPrice(),buyer,phone,member,item.productName(),null,
     group.getExpiresAt(),item.itemId(),expected,item.discountAmount());
    order.attachGroup(group,item.version());orders.saveAndFlush(order);
    if(stock.reserve(s.getId(),item.quantity())!=1)throw conflict("재고가 부족합니다.");
    if(stock.findById(s.getId()).orElseThrow().getAvailable()==0)sales.transitionStatus(s.getId(),"ON_SALE","SOLD_OUT");
   }
   return new Creation(response(groups.findById(group.getId()).orElseThrow()),true);
  });
 }
 public PaymentGroup bindSingle(Order order,String requestKey,Long sourceVersion){
  String key="single:"+(requestKey==null?UUID.randomUUID():requestKey);
  // Order idempotency has its own key; avoid group-key collisions with cart requests.
  if(key.length()>64)key="single:"+fingerprint(key).substring(0,57);
  PaymentGroup group=groups.saveAndFlush(new PaymentGroup("PG-"+UUID.randomUUID(),order.getMemberId(),key,fingerprint(order.getOrderNumber()),order.getTotalAmount(),order.getExpiresAt()));
  order.attachGroup(group,sourceVersion);orders.saveAndFlush(order);return group;
 }
 public GroupResponse get(String member,String number){return tx.execute(t->response(owned(member,number)));}
 public Optional<GroupResponse> active(String member){return tx.execute(t->groups.findByMemberIdAndStatusIn(member,ACTIVE).stream().findFirst().map(this::response));}
 public PaymentAttempt start(String member,String number,String key){
  validateKey(key);PaymentGroup found=owned(member,number);guard.ensure(member);
  return tx.execute(t->{
   guard.lock(member);PaymentGroup g=lockedGroup(found.getId());
   if(g.getPaymentId()!=null){
    PaymentAttempt previous=payments.findById(g.getPaymentId()).orElseThrow();
    if(Objects.equals(previous.getRequestKey(),key))return previous;
    throw conflict("이미 결제를 시작했습니다. 기존 결과를 확인해 주세요.");
   }
   if(g.getStatus()!=OrderStatus.PENDING_PAYMENT || !g.getExpiresAt().isAfter(Instant.now()))throw conflict("결제를 시작할 수 없는 주문입니다.");
   List<Order> children=orders.findByPaymentGroupIdOrderByIdAsc(g.getId());
   DevPaymentScenarioRegistry registry=scenarios.getIfAvailable();
   PaymentScenario scenario=registry==null?PaymentScenario.INSTANT_SUCCESS:registry.get(number).orElseGet(()->registry.get(children.getFirst().getOrderNumber()).orElse(PaymentScenario.INSTANT_SUCCESS));
   Instant requestedAt=Instant.now();
   PaymentAttempt attempt=new PaymentAttempt(children.getFirst().getId(),scenario,requestedAt);attempt.attachGroup(g.getId(),key);
   if(g.getPayableAmount()==0)attempt.completeWithoutCharge(requestedAt);
   attempt=payments.saveAndFlush(attempt);g.transition(OrderStatus.PAYMENT_CONFIRMING);g.setPaymentId(attempt.getId());groups.saveAndFlush(g);
   for(Order child:children)if(orders.transitionStatus(child.getId(),"PENDING_PAYMENT","PAYMENT_CONFIRMING")!=1)throw new IllegalStateException("group order transition failed");
   if(g.getPayableAmount()==0){
    for(Order child:children)if(child.getSourceCartItemId()!=null)cart.lockOwned(child.getSourceCartItemId(),member);
    for(Order child:children.stream().sorted(Comparator.comparing(Order::getSalesInfoId)).toList())guard.lockSales(child.getSalesInfoId());
    couponReservations.confirm(member,g.getCouponId());
    for(Order child:children){
     if(orders.transitionStatus(child.getId(),"PAYMENT_CONFIRMING","PAID")!=1)throw new IllegalStateException("zero payment order transition failed");
     if(stock.consumeReserved(child.getSalesInfoId(),child.getQuantity())!=1)throw new IllegalStateException("reserved stock missing");
     if(child.getSourceCartItemId()!=null && child.getSourceCartItemVersion()!=null)
      jdbc.update("DELETE FROM cart_item WHERE id=? AND member_id=? AND version=?",child.getSourceCartItemId(),member,child.getSourceCartItemVersion());
    }
    g.transition(OrderStatus.PAID);groups.saveAndFlush(g);
    return attempt;
   }
   Long id=attempt.getId();
   TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){engine.schedule(id,scenario);}});
   return attempt;
  });
 }
 public PaymentAttempt payment(String member,String number,Long id){
  PaymentGroup g=owned(member,number);PaymentAttempt p=payments.findById(id).orElseThrow(()->error(ErrorCode.NOT_FOUND,"결제를 찾을 수 없습니다."));
  if(!Objects.equals(p.getPaymentGroupId(),g.getId()))throw error(ErrorCode.NOT_FOUND,"결제를 찾을 수 없습니다.");return p;
 }
 public void cancel(String member,String number){
  PaymentGroup found=owned(member,number);guard.ensure(member);
  tx.executeWithoutResult(t->{guard.lock(member);PaymentGroup g=lockedGroup(found.getId());
   if(g.getStatus()==OrderStatus.CANCELLED)return;
   if(g.getStatus()!=OrderStatus.PENDING_PAYMENT)throw conflict("결제 전 주문만 취소할 수 있습니다.");
   close(g,OrderStatus.CANCELLED);
  });
 }
 public boolean expire(Long groupId){
  PaymentGroup found=groups.findById(groupId).orElseThrow();guard.ensure(found.getMemberId());
  return tx.execute(t->{guard.lock(found.getMemberId());PaymentGroup g=lockedGroup(groupId);
   if(g.getStatus()!=OrderStatus.PENDING_PAYMENT || g.getExpiresAt().isAfter(Instant.now()))return false;
   close(g,OrderStatus.EXPIRED);return true;
  });
 }
 public boolean resolve(Long id){
  PaymentAttempt attempt=payments.findById(id).orElseThrow();
  if(attempt.getStatus().isTerminal())return false;
  // Commit approval independently before applying it to order/inventory state.
  PaymentStatus result=gateway.authorize(id,attempt.getScenario());
  PaymentGroup found=groups.findById(attempt.getPaymentGroupId()).orElseThrow();guard.ensure(found.getMemberId());
  return tx.execute(t->{
   guard.lock(found.getMemberId());PaymentGroup g=lockedGroup(found.getId());
   if(g.getStatus()!=OrderStatus.PAYMENT_CONFIRMING)return false;
   List<Order> children=orders.findByPaymentGroupIdOrderByIdAsc(g.getId());
   // Cart before stock matches creation; all stock locks follow ascending sales id.
   for(Order child:children)if(child.getSourceCartItemId()!=null)cart.lockOwned(child.getSourceCartItemId(),g.getMemberId());
   for(Order child:children.stream().sorted(Comparator.comparing(Order::getSalesInfoId)).toList())guard.lockSales(child.getSalesInfoId());
   OrderStatus finalStatus=result==PaymentStatus.SUCCESS?OrderStatus.PAID:OrderStatus.FAILED;
   if(payments.resolveIfProcessing(id,result.name())!=1)throw new IllegalStateException("payment result inconsistent");
   if(result==PaymentStatus.SUCCESS)couponReservations.confirm(g.getMemberId(),g.getCouponId());
   else couponReservations.release(g.getMemberId(),g.getCouponId());
   for(Order child:children){
    if(orders.transitionStatus(child.getId(),"PAYMENT_CONFIRMING",finalStatus.name())!=1)throw new IllegalStateException("group order inconsistent");
    if(result==PaymentStatus.SUCCESS){
     if(stock.consumeReserved(child.getSalesInfoId(),child.getQuantity())!=1)throw new IllegalStateException("reserved stock missing");
     if(child.getSourceCartItemId()!=null && child.getSourceCartItemVersion()!=null)
      jdbc.update("DELETE FROM cart_item WHERE id=? AND member_id=? AND version=?",child.getSourceCartItemId(),g.getMemberId(),child.getSourceCartItemVersion());
    }else restore(child);
   }
   g.transition(finalStatus);groups.saveAndFlush(g);return true;
  });
 }
 private PaymentGroup lockedGroup(Long id){PaymentGroup g=groups.lockById(id).orElseThrow();entityManager.refresh(g);return g;}
 private void close(PaymentGroup g,OrderStatus terminal){
  List<Order> children=orders.findByPaymentGroupIdOrderByIdAsc(g.getId()).stream().sorted(Comparator.comparing(Order::getSalesInfoId)).toList();
  for(Order child:children)guard.lockSales(child.getSalesInfoId());
  for(Order child:children){
   int changed=terminal==OrderStatus.CANCELLED?orders.cancelOrder(child.getId()):jdbc.update("UPDATE orders SET status='EXPIRED',cancelled_at=CURRENT_TIMESTAMP,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='PENDING_PAYMENT'",child.getId());
   if(changed!=1)throw new IllegalStateException("group close inconsistent");restore(child);
  }
  couponReservations.release(g.getMemberId(),g.getCouponId());
  g.transition(terminal);groups.saveAndFlush(g);
 }
 private void restore(Order child){if(stock.restoreReserved(child.getSalesInfoId(),child.getQuantity())!=1)throw new IllegalStateException("reserved stock missing");sales.reopenIfStockAvailable(child.getSalesInfoId());}
 private PaymentGroup owned(String member,String number){return groups.findByGroupNumberAndMemberId(number,member).orElseThrow(()->error(ErrorCode.NOT_FOUND,"주문 묶음을 찾을 수 없습니다."));}
 private GroupResponse response(PaymentGroup g){return new GroupResponse(g.getGroupNumber(),g.getStatus(),g.getTotalAmount(),g.getDiscountAmount(),g.getPayableAmount(),g.getCouponId(),g.getExpiresAt(),orders.findByPaymentGroupIdOrderByIdAsc(g.getId()).stream().map(OrderResponse::from).toList(),g.getPaymentId());}
 private Creation replay(PaymentGroup g,String hash){if(!g.getFingerprint().equals(hash))throw conflict("다른 주문 요청에 사용된 멱등키입니다.");return new Creation(get(g.getMemberId(),g.getGroupNumber()),false);}
 private List<Selection> validateSelections(List<Selection> inputs){
  if(inputs==null || inputs.isEmpty() || inputs.size()>100)throw error(ErrorCode.INVALID_REQUEST,"상품을 1~100개 선택해 주세요.");
  Set<Long> seen=new HashSet<>();for(Selection s:inputs)if(s==null || s.itemId()==null || s.itemId()<1 || s.version()==null || s.version()<0 || !seen.add(s.itemId()))throw error(ErrorCode.INVALID_REQUEST,"항목 ID·버전 또는 중복 선택이 올바르지 않습니다.");
  return inputs.stream().sorted(Comparator.comparing(Selection::itemId)).toList();
 }
 private static void validateKey(String key){if(key==null || key.isBlank() || key.length()>64)throw error(ErrorCode.INVALID_REQUEST,"멱등키는 1~64자여야 합니다.");}
 private String fingerprint(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));}catch(Exception e){throw new IllegalStateException(e);}}
 private static long add(long a,long b){try{return Math.addExact(a,b);}catch(ArithmeticException e){throw error(ErrorCode.INVALID_REQUEST,"주문 금액이 너무 큽니다.");}}
 private static BusinessException conflict(String message){return error(ErrorCode.CONFLICT,message);}
 private static BusinessException error(ErrorCode code,String message){return new BusinessException(code,message);}
}
