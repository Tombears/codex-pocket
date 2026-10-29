package dev.codexpocket;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReconnectPolicyTest {
    @Test public void retryIsSerializedAndBacksOff(){ReconnectPolicy p=new ReconnectPolicy();assertTrue(p.begin("a",0));assertFalse(p.begin("a",0));p.failed("a",100);assertFalse(p.begin("a",5099));assertTrue(p.begin("a",5100));p.failed("a",5100);assertFalse(p.begin("a",15099));assertTrue(p.begin("a",15100));}
    @Test public void networkRecoveryAndDisconnectResetRetry(){ReconnectPolicy p=new ReconnectPolicy();assertTrue(p.begin("a",0));p.failed("a",0);p.networkAvailable();assertTrue(p.begin("a",1));p.succeeded("a");assertTrue(p.begin("a",2));p.failed("a",2);p.forget("a");assertTrue(p.begin("a",3));}
    @Test public void repeatedFailuresAreBoundedAndHostsAreIndependent(){ReconnectPolicy p=new ReconnectPolicy();long now=0;for(int i=0;i<20;i++){assertTrue(p.begin("a",now));p.failed("a",now);assertFalse(p.begin("a",now));now+=300_000;}assertTrue(p.begin("b",0));assertTrue(p.begin("a",now));}
}
