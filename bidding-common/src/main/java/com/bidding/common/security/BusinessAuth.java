package com.bidding.common.security;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;

/**
 * 招标方 / 投标方通用业务鉴权。
 */
public final class BusinessAuth {

    private BusinessAuth() {
    }

    /**
     * 要求已登录且为招标方或投标方。
     */
    public static CurrentUser requireTendererOrBidder(String forbiddenMessage) {
        CurrentUser user = UserContext.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (!user.isTenderer() && !user.isBidder()) {
            String msg = forbiddenMessage;
            if (msg == null || msg.isBlank()) {
                msg = "仅招标方或投标方可操作";
            }
            throw new BizException(ErrorCode.FORBIDDEN, msg);
        }
        return user;
    }
}
