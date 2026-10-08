package com.shoppinglive.commerce.purchase.application;
import com.shoppinglive.commerce.purchase.infrastructure.PaymentGroupRepository;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.data.domain.PageRequest;
import org.slf4j.LoggerFactory;
import java.time.Instant;
@Component
public class PaymentGroupExpirationScheduler {
 private final PaymentGroupRepository groups;private final PaymentGroupService service;
 public PaymentGroupExpirationScheduler(PaymentGroupRepository groups,PaymentGroupService service){this.groups=groups;this.service=service;}
 @Scheduled(initialDelayString="PT1M",fixedDelayString="PT1M")
 public void run(){for(Long id:groups.expired(Instant.now(),PageRequest.of(0,100)))try{service.expire(id);}catch(RuntimeException e){LoggerFactory.getLogger(getClass()).warn("group expiry failed id={}",id,e);}}
}
