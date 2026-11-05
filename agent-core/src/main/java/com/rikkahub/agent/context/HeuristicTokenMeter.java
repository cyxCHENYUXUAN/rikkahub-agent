package com.rikkahub.agent.context;

/**
 * Conservative local estimate: non-ASCII code points count as one token each;
 * ASCII is grouped at four characters per token. This is an estimate only; the
 * authority for billing remains provider usage.
 */
public final class HeuristicTokenMeter implements TokenMeter {
    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long ascii = 0;
        long other = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            if (codePoint > 127) {
                other++;
            } else {
                ascii++;
            }
            index += Character.charCount(codePoint);
        }
        long total = other + (ascii + 3) / 4;
        return total >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }
}
