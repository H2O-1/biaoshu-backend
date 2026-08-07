package com.bidding.review.infrastructure.recall;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 极简分词：英文/数字按词；中文连续段出单字 + 双字（便于短 query 命中）。
 */
final class SimpleTextTokenizer {

    private SimpleTextTokenizer() {
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        StringBuilder latin = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isLatinOrDigit(cp)) {
                flushCjk(cjk, tokens);
                latin.appendCodePoint(Character.toLowerCase(cp));
            } else if (isCjk(cp)) {
                flushLatin(latin, tokens);
                cjk.appendCodePoint(cp);
            } else {
                flushLatin(latin, tokens);
                flushCjk(cjk, tokens);
            }
        }
        flushLatin(latin, tokens);
        flushCjk(cjk, tokens);
        return tokens;
    }

    private static void flushLatin(StringBuilder latin, List<String> tokens) {
        if (latin.length() == 0) {
            return;
        }
        tokens.add(latin.toString().toLowerCase(Locale.ROOT));
        latin.setLength(0);
    }

    private static void flushCjk(StringBuilder cjk, List<String> tokens) {
        if (cjk.length() == 0) {
            return;
        }
        String s = cjk.toString();
        cjk.setLength(0);
        int n = s.codePointCount(0, s.length());
        int[] cps = s.codePoints().toArray();
        for (int i = 0; i < n; i++) {
            tokens.add(new String(cps, i, 1));
            if (i + 1 < n) {
                tokens.add(new String(cps, i, 2));
            }
        }
    }

    private static boolean isLatinOrDigit(int cp) {
        return (cp >= 'a' && cp <= 'z')
                || (cp >= 'A' && cp <= 'Z')
                || (cp >= '0' && cp <= '9');
    }

    private static boolean isCjk(int cp) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION;
    }
}
