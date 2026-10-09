package com.shoppinglive.contracts.events;

/** Wire-level mirror of the four Commerce sales states; kept here to avoid a service dependency. */
public enum SalesInfoStatus {
    READY,
    ON_SALE,
    SOLD_OUT,
    PRIVATE
}
