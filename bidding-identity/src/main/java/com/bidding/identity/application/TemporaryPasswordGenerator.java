package com.bidding.identity.application;

import java.security.SecureRandom;

/**
 * 生成符合密码策略的临时密码（含字母+数字，长度 12）。
 * 仅用于管理员重置；明文只在接口响应中返回一次。
 */
public final class TemporaryPasswordGenerator {

    private static final int LENGTH = 12;
    private static final char[] LETTERS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final char[] DIGITS = "23456789".toCharArray();
    private static final char[] ALL;

    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        ALL = new char[LETTERS.length + DIGITS.length];
        System.arraycopy(LETTERS, 0, ALL, 0, LETTERS.length);
        System.arraycopy(DIGITS, 0, ALL, LETTERS.length, DIGITS.length);
    }

    private TemporaryPasswordGenerator() {
    }

    public static String generate() {
        char[] chars = new char[LENGTH];
        // 先各放一个字母和数字，保证策略通过
        chars[0] = LETTERS[RANDOM.nextInt(LETTERS.length)];
        chars[1] = DIGITS[RANDOM.nextInt(DIGITS.length)];
        for (int i = 2; i < LENGTH; i++) {
            chars[i] = ALL[RANDOM.nextInt(ALL.length)];
        }
        // 简单打乱，避免前两位固定模式
        for (int i = LENGTH - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
        return new String(chars);
    }
}
