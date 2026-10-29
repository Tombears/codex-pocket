package dev.codexpocket;

import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticsTest {
    @Test public void malformedNumericAddressesAreRejectedBeforeDns(){assertThrows(IllegalArgumentException.class,()->SshAddress.normalize("223.25153.168"));assertThrows(IllegalArgumentException.class,()->SshAddress.normalize("256.1.1.1"));assertThrows(IllegalArgumentException.class,()->SshAddress.normalize("1.2.3."));}
    @Test public void ipAndDomainInputsAreNormalized(){assertEquals("192.0.2.10",SshAddress.normalize(" 192.0.2.10 "));assertEquals("server.example.com",SshAddress.normalize("server.example.com"));assertEquals("::1",SshAddress.normalize("[::1]"));assertThrows(IllegalArgumentException.class,()->SshAddress.normalize("ssh://root@example.com"));}
    @Test public void secretsAreRemovedBeforeLogStorage(){String s=LogPolicy.safe("failure\npassword=hidden value\n-----BEGIN OPENSSH PRIVATE KEY-----\nabcdef\n-----END OPENSSH PRIVATE KEY-----\nAuthorization: Bearer confidential\n");assertFalse(s.contains("hidden value"));assertFalse(s.contains("abcdef"));assertFalse(s.contains("confidential"));assertTrue(s.contains("failure"));}
    @Test public void logsHaveOneDayRetentionAndBoundedMessages(){long now=1_000_000_000L;assertEquals(86_400_000L,now-LogPolicy.cutoff(now));assertEquals(2000,LogPolicy.safe("x".repeat(3000)).length());}
}
