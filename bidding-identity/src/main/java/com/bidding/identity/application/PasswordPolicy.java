package com.bidding.identity.application;

import com.bidding.common.error.BizException;
import com.bidding.common.error.ErrorCode;

/**
 * 密码规则：至少 8 位，且同时包含字母和数字。
 * （两次输入一致由前端校验，后端不重复校验）
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;

    private PasswordPolicy() {
    }

    public static void validateNewPassword(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码至少 " + MIN_LENGTH + " 位");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码须同时包含字母和数字");
        }
    }
}
