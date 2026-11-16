package com.shoppinglive.commercecore.payment.relay;

import static com.shoppinglive.commercecore.payment.relay.PaymentRelayContract.*;

public interface PaymentRelayClient {
    Reply<Created> create(Create request);

    Reply<Checkout> checkout(String orderRef);

    Reply<Lookup> get(String orderRef);

    Reply<ApprovalResult> approve(String orderRef, Approval request);
}
