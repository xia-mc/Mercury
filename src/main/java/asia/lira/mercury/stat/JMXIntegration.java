package asia.lira.mercury.stat;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

public class JMXIntegration {
    private static final Logger LOGGER = LogManager.getLogger(JMXIntegration.class);

    public static void initialize() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            mbs.registerMBean(FastMacroStats.getInstance(), new ObjectName("mercury:type=MacroCache"));
        } catch (Exception e) {
            LOGGER.warn("Failed to initialize JMXIntegration, ignored.", e);
        }
    }
}
