package dev.codexpocket;

import java.util.Set;

final class ApprovalMode {
    static String require(String mode){
        if(!Set.of("on-request","untrusted","never").contains(mode))throw new IllegalArgumentException("审批模式无效");
        return mode;
    }
}
