package dev.codexpocket;

import java.util.regex.Pattern;

final class LogPolicy {
    static final long MAX_AGE_MS=24L*60*60*1000;
    static final int MAX_ROWS=6000;
    private static final Pattern KEY=Pattern.compile("(?s)-----BEGIN[^\\r\\n]*PRIVATE KEY-----.*?(?:-----END[^\\r\\n]*PRIVATE KEY-----|$)");
    private static final Pattern SECRET=Pattern.compile("(?im)(password|passwd|passphrase|api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|secret|验证码)([\\s\"']*[:=])[^\\r\\n]*");
    private static final Pattern TOKEN=Pattern.compile("(?i)\\b(?:sk-[a-z0-9_-]{12,}|gh[pousr]_[a-z0-9_]{12,}|github_pat_[a-z0-9_]{12,}|Bearer\\s+[^\\s]+)");
    static String safe(String text){
        String value=text==null?"":text;
        value=KEY.matcher(value).replaceAll("[PRIVATE KEY REDACTED]");
        value=SECRET.matcher(value).replaceAll("$1$2[REDACTED]");
        value=TOKEN.matcher(value).replaceAll("[TOKEN REDACTED]");
        return value.substring(0,Math.min(2000,value.length()));
    }
    static long cutoff(long now){return now-MAX_AGE_MS;}
}
