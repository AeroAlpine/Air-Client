package com.coflnet.lowballing;

/** What the visitor probably wants, based on a short chat line. */
public enum LowballingChatIntent {
    PRICE_OFFER,
    ACCEPT,
    REFUSE,
    COMPLAINT,
    INSULT,
    VALUE_CLAIM,
    AH_OFFER,
    WEALTH_QUESTION,
    ITEM_MENTION,
    QUESTION,
    WAIT,
    GREETING,
    CONTEXT_LESS,
    UNKNOWN
}
