import io.github.bandbridge.core.proto.CheckSumsSelfTest;
import io.github.bandbridge.core.proto.HuaweiPacketFrameSelfTest;
import io.github.bandbridge.core.proto.HuaweiUtilSelfTest;
import io.github.bandbridge.core.proto.LegacySleepNightsSelfTest;
import io.github.bandbridge.core.proto.TlvSelfTest;
import io.github.bandbridge.core.proto.hr.RriBeatsSelfTest;
import io.github.bandbridge.core.testing.T;
import io.github.bandbridge.core.transport.PacketAssemblerSelfTest;

/**
 * Entry point for the bandcore offline self-test.
 *
 * Runs every {@code *SelfTest} class against the real protocol sources compiled
 * for a plain JVM (no Android device, no Bluetooth, no Gradle, no network).
 * Exit code 0 = all checks passed. See docs/TESTING.md.
 */
public final class SelfTestRunner {

    private SelfTestRunner() {}

    public static void main(String[] args) throws Exception {
        System.out.println("bandcore offline self-test");
        System.out.println("  java: " + System.getProperty("java.version")
                + "  os: " + System.getProperty("os.name"));
        System.out.println("  (pure protocol logic only; no device, no Bluetooth, no network)");

        TlvSelfTest.run();
        CheckSumsSelfTest.run();
        HuaweiUtilSelfTest.run();
        HuaweiPacketFrameSelfTest.run();
        PacketAssemblerSelfTest.run();
        RriBeatsSelfTest.run();
        LegacySleepNightsSelfTest.run();

        System.exit(T.summary());
    }
}
