package org.ai.agent.ddbknowledge;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

@SpringBootApplication
@Slf4j
public class DdbKnowledgeAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DdbKnowledgeAgentApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        printAccessUrls();
    }

    private void printAccessUrls() {
        String wslIp = "127.0.0.1";
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface netint : Collections.list(nets)) {
                if (netint.isLoopback() || !netint.isUp()) continue;

                Enumeration<InetAddress> inetAddresses = netint.getInetAddresses();
                for (InetAddress inetAddress : Collections.list(inetAddresses)) {
                    if (inetAddress instanceof Inet4Address) {
                        wslIp = inetAddress.getHostAddress();
                        break;
                    }
                }
                if (!wslIp.equals("127.0.0.1")) break;
            }
        } catch (Exception e) {
            // Fallback to loopback
        }

        log.info("\n----------------------------------------------------------\n" +
                 "🚀 DDB Knowledge Agent is ready!\n" +
                 "Local UI:   http://localhost:8090\n" +
                 "WSL UI IP:  http://{}:8090\n" +
                 "PGWeb UI:   http://localhost:5433\n" +
                 "----------------------------------------------------------", wslIp);
    }
}
