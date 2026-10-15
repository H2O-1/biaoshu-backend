package com.bidding.common.security;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;

/**
 * 投标方业务鉴权小工具：登录校验、BIDDER 角色。
 */
public final class BidderAuth {

    private BidderAuth() {
    }

    /**
     * 要求已登录且为投标方；默认文案「仅投标方可操作」。
     */
    public static CurrentUser requireBidder() {
        return requireBidder("仅投标方可操作");
    }

    /**
     * 要求已登录且为投标方。
     *
     * @param forbiddenMessage 非投标方时的 403 文案
     */
    public static CurrentUser requireBidder(String forbiddenMessage) {
        CurrentUser user = UserContext.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (!user.isBidder()) {
            String msg = forbiddenMessage;
            if (msg == null || msg.isBlank()) {
                msg = "仅投标方可操作";
            }
            throw new BizException(ErrorCode.FORBIDDEN, msg);
        }
        return user;
    }
}
